package com.nuvio.app.features.cast

import android.content.Context
import android.util.Log
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastMediaProbe
import com.nuvio.app.features.settings.NuvioEnhancedSettingsRepository
import com.nuvio.app.features.settings.NuvioEnhancedSettingsUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

actual object CastDelivery {

    private const val TAG = "CastDelivery"
    private const val DIRECT_PLAY_PROBE_TIMEOUT_MS = 8_000L

    private const val TS_CONTENT_TYPE = "video/mp2t"

    /** About ten seconds of a typical HLS rendition, so the renderer's first read is not starved. */
    private const val HLS_HEAD_START_BYTES = 512L * 1024
    private const val HLS_START_TIMEOUT_MS = 30_000L
    private const val HLS_START_POLL_MS = 250L

    private var appContext: Context? = null
    private var processor: CastMediaProcessor? = null
    private val server = CastLocalServer()
    private var publishedId: String? = null
    private var workingFile: File? = null
    private var liveJob: LiveRemuxJob? = null

    private val _status = MutableStateFlow<CastDeliveryStatus>(CastDeliveryStatus.Idle)
    actual val status: StateFlow<CastDeliveryStatus> = _status.asStateFlow()

    /** Android-only entry point; call alongside [CastPlatform.initialize]. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual suspend fun cast(request: CastStreamRequest): Result<Unit> {
        val context = appContext
            ?: return fail("Casting is not initialised")

        val receiver = resolveActiveReceiver() ?: return fail("No Cast device connected")

        releasePrevious()

        val capabilities = receiver.capabilities

        // --- Probe ---------------------------------------------------------------------
        _status.value = CastDeliveryStatus.Probing
        val probeOrNull: suspend () -> CastMediaProbe? = {
            probeCastMedia(request.url, request.headers).getOrElse { error ->
                // A probe failure is not fatal. The receiver may well handle the stream, so fall
                // back to an optimistic direct play rather than refusing to cast at all.
                Log.w(TAG, "Probe failed, attempting direct play", error)
                null
            }
        }
        val probe = if (receiver.directPlayOnly) {
            // A direct-play receiver only uses the probe for the container and duration, so a
            // slow origin must not hold the cast up. The extractor's blocking read cannot be
            // interrupted, so it runs in a detached scope and is abandoned on timeout rather
            // than waited for.
            val pending = CoroutineScope(Dispatchers.IO).async { probeOrNull() }
            withTimeoutOrNull(DIRECT_PLAY_PROBE_TIMEOUT_MS) { pending.await() } ?: run {
                if (pending.isActive) Log.w(TAG, "Probe did not finish in time, attempting direct play")
                pending.cancel()
                null
            }
        } else {
            probeOrNull()
        }

        val directPlan = CastDeliveryPlan(
            mode = CastDeliveryMode.DIRECT,
            reasons = emptyList(),
            videoTarget = null,
            audioTarget = null,
            targetContainer = probe?.container?.takeUnless { it == CastContainer.UNKNOWN }
                ?: containerFromUrl(request.url),
            requiresLocalServer = !request.sourceReachableByReceiver,
        )
        val settings = NuvioEnhancedSettingsRepository.run {
            ensureLoaded()
            uiState.value
        }
        val hlsAsTs = receiver.directPlayOnly &&
            directPlan.targetContainer == CastContainer.HLS &&
            shouldConvertHls(settings)
        val plan = when {
            hlsAsTs || probe == null -> directPlan
            receiver.directPlayOnly && !settings.castAlwaysConvert -> directPlan
            else -> CastDeliveryPlanner.plan(probe, capabilities, request.sourceReachableByReceiver)
        }

        if (plan.mode == CastDeliveryMode.UNSUPPORTED) {
            return fail("${receiver.name} cannot play video")
        }

        // --- Produce a playable source -------------------------------------------------
        val contentUrl: String
        val contentType: String

        if (hlsAsTs) {
            contentType = TS_CONTENT_TYPE
            contentUrl = startHlsAsTs(context, request).getOrElse { error ->
                return fail(error.message ?: "Could not convert this M3U8 stream for casting")
            }
        } else if (plan.mode == CastDeliveryMode.DIRECT) {
            contentType = contentTypeFor(plan.targetContainer)
            contentUrl = if (plan.requiresLocalServer) {
                val id = newId()
                publishedId = id
                server.publish(
                    id,
                    CastLocalServer.Payload.Proxy(request.url, contentType, request.headers),
                ) ?: return fail("Phone is not on a network the TV can reach")
            } else {
                request.url
            }
        } else {
            _status.value = CastDeliveryStatus.Preparing(plan.mode, -1, plan.reasons)

            val engine = processor ?: castMediaProcessor(context).also { processor = it }
            val output = File(context.cacheDir, "cast/${newId()}.mp4")
            workingFile = output

            val produced = engine.process(
                sourceUrl = request.url,
                plan = plan,
                output = output,
                durationMs = probe?.durationMs,
                headers = request.headers,
            ) { progress ->
                _status.value = CastDeliveryStatus.Preparing(plan.mode, progress.percent, plan.reasons)
            }.getOrElse { error ->
                Log.w(TAG, "${plan.mode} failed", error)
                return fail(
                    when (plan.mode) {
                        CastDeliveryMode.REMUX -> "Could not repackage this file for casting"
                        else -> "Could not convert this file for casting"
                    },
                )
            }

            contentType = "video/mp4"
            val id = newId()
            publishedId = id
            contentUrl = server.publish(id, CastLocalServer.Payload.LocalFile(produced, contentType))
                ?: return fail("Phone is not on a network the TV can reach")
        }

        // --- Hand off ------------------------------------------------------------------
        val result = receiver.deliver(
            CastMediaRequest(
                contentUrl = contentUrl,
                contentType = contentType,
                title = request.title,
                subtitle = request.subtitle,
                posterUrl = request.posterUrl,
                startPositionMs = request.startPositionMs,
                durationMs = probe?.durationMs,
                isLive = probe?.isLive == true && probe.durationMs == null,
                subtitles = request.subtitles,
            ),
        )

        return result.fold(
            onSuccess = {
                _status.value = CastDeliveryStatus.Playing(if (hlsAsTs) CastDeliveryMode.REMUX else plan.mode)
                Result.success(Unit)
            },
            onFailure = { error -> fail(error.message ?: "The TV refused the stream") },
        )
    }

    actual fun cancel() {
        processor?.cancel()
        releasePrevious()
        _status.value = CastDeliveryStatus.Idle
    }

    /**
     * Whether to repackage an M3U8 stream for a DLNA renderer, per the Casting settings: never
     * while "Convert M3U8" is off, otherwise always or after asking.
     */
    private suspend fun shouldConvertHls(settings: NuvioEnhancedSettingsUiState): Boolean {
        if (!settings.castConvertHls) return false
        if (!settings.castConvertHlsAsk) return true
        return CastConversionPrompt.ask(
            title = "Convert M3U8?",
            message = "This is an M3U8 stream, which many TVs cannot play. Convert it to a TS stream on your phone?",
            confirmLabel = "Convert",
            declineLabel = "Play as is",
        )
    }

    /**
     * Starts repackaging the M3U8 stream into a TS file and publishes it while it is still being
     * written, once there is enough of a head start for the renderer's first read. Returns the
     * URL to hand the renderer.
     */
    private suspend fun startHlsAsTs(context: Context, request: CastStreamRequest): Result<String> {
        _status.value = CastDeliveryStatus.Preparing(CastDeliveryMode.REMUX, -1, emptyList())
        val output = File(context.cacheDir, "cast/${newId()}.ts")
        workingFile = output
        val job = startHlsToTsRemux(request.url, request.headers, output)
            ?: return Result.failure(IllegalStateException("This build cannot convert M3U8 streams"))
        liveJob = job

        val started = try {
            withTimeoutOrNull(HLS_START_TIMEOUT_MS) {
                while (output.length() < HLS_HEAD_START_BYTES && !job.isFinished) delay(HLS_START_POLL_MS)
                true
            }
        } catch (cancelled: CancellationException) {
            job.cancel()
            throw cancelled
        }
        job.failure?.let { detail ->
            Log.w(TAG, "M3U8 conversion failed: $detail")
            return Result.failure(IllegalStateException("Could not convert this M3U8 stream for casting"))
        }
        if (started == null) {
            job.cancel()
            return Result.failure(IllegalStateException("The M3U8 stream did not start in time"))
        }

        val id = newId()
        publishedId = id
        val url = server.publish(id, CastLocalServer.Payload.GrowingFile(output, TS_CONTENT_TYPE, job))
            ?: return Result.failure(IllegalStateException("Phone is not on a network the TV can reach"))
        return Result.success(url)
    }

    private fun releasePrevious() {
        liveJob?.cancel()
        liveJob = null
        publishedId?.let(server::unpublish)
        publishedId = null
        workingFile?.let { file -> runCatching { if (file.exists()) file.delete() } }
        workingFile = null
    }

    private fun fail(message: String): Result<Unit> {
        _status.value = CastDeliveryStatus.Failed(message)
        return Result.failure(IllegalStateException(message))
    }

    private fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "")
}
