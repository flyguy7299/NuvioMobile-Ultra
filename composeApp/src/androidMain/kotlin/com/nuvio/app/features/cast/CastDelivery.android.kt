package com.nuvio.app.features.cast

import android.content.Context
import android.util.Log
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastMediaProbe
import com.nuvio.app.features.cast.ui.describeCastIncompatibility
import com.nuvio.app.features.settings.NuvioEnhancedSettingsRepository
import com.nuvio.app.features.settings.NuvioEnhancedSettingsUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong

actual object CastDelivery {

    private const val TAG = "CastDelivery"
    private const val DIRECT_PLAY_PROBE_TIMEOUT_MS = 8_000L

    private const val TS_CONTENT_TYPE = "video/mp2t"

    /** About ten seconds of a typical HLS rendition, so the renderer's first read is not starved. */
    private const val HLS_HEAD_START_BYTES = 512L * 1024
    private const val HLS_MIN_START_BYTES = 64L * 1024
    private const val HLS_MIN_START_WAIT_MS = 5_000L
    private const val HLS_START_TIMEOUT_MS = 30_000L
    private const val HLS_START_POLL_MS = 250L
    private const val LIVE_JOB_WATCH_MS = 1_000L

    private var appContext: Context? = null
    private var processor: CastMediaProcessor? = null
    private val server = CastLocalServer()
    private var publishedId: String? = null
    private var workingFile: File? = null
    private var liveJob: LiveRemuxJob? = null

    /** One delivery at a time: they share every field above. */
    private val deliveryLock = Mutex()
    @Volatile private var activeDelivery: Job? = null
    private val castGeneration = AtomicLong()
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow<CastDeliveryStatus>(CastDeliveryStatus.Idle)
    actual val status: StateFlow<CastDeliveryStatus> = _status.asStateFlow()

    /** Android-only entry point; call alongside [CastPlatform.initialize]. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        // Nothing is casting yet, so anything in cast/ was left by a process that was killed
        // mid-cast — possibly a multi-gigabyte live remux — and would otherwise stay forever.
        // Only files older than this moment, so a cast started before the purge thread gets to
        // the directory keeps its own file.
        val leftovers = File(context.cacheDir, "cast")
        val cutoff = System.currentTimeMillis()
        Thread({
            leftovers.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { runCatching { it.delete() } }
        }, "cast-cache-purge").start()
    }

    actual suspend fun cast(request: CastStreamRequest): Result<Unit> {
        // A superseded cast can still be unwinding from a blocking call (a SOAP request, the
        // probe), so it is cancelled and waited out rather than left to release or overwrite the
        // resources this one is about to create.
        activeDelivery?.cancel()
        // Only the newest request may run: a cast still queued for the lock when a newer one (or
        // cancel()) arrives gives up instead of running after it.
        val generation = castGeneration.incrementAndGet()
        return deliveryLock.withLock {
            if (generation != castGeneration.get()) throw CancellationException("Superseded by a newer cast")
            val self = currentCoroutineContext()[Job]
            activeDelivery = self
            try {
                deliver(request)
            } catch (cancelled: CancellationException) {
                // Leaving the player, or Stop casting, mid-cast: nothing this cast created is
                // wanted, and the status would otherwise strand at Probing/Preparing, which the
                // player's banner then shows the next time it opens.
                releasePrevious()
                _status.value = CastDeliveryStatus.Idle
                throw cancelled
            } finally {
                if (activeDelivery === self) activeDelivery = null
            }
        }
    }

    private suspend fun deliver(request: CastStreamRequest): Result<Unit> {
        val context = appContext
            ?: return fail("Casting is not initialised")

        val receiver = resolveActiveReceiver() ?: return fail("No Cast device connected")

        releasePrevious()

        val capabilities = receiver.capabilities
        val settings = NuvioEnhancedSettingsRepository.run {
            ensureLoaded()
            uiState.value
        }

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
        val probe = if (receiver.prefersDirectPlay && !settings.castAlwaysConvert) {
            // Direct play only uses the probe for the container and duration, so a slow origin
            // must not hold the cast up. Under "Always convert" the probe decides whether to
            // convert, so it gets the full time instead. The extractor's blocking read cannot be
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
        val dlnaHls = receiver.prefersDirectPlay && directPlan.targetContainer == CastContainer.HLS
        val hlsAsTs = dlnaHls && shouldConvertHls(settings)
        val plan = when {
            // For a DLNA renderer an M3U8 stream is either converted live ("Convert M3U8") or
            // sent as is. The whole-file path below would have to download the entire stream
            // before playing, and would ask a second time after "Play as is".
            hlsAsTs || dlnaHls || probe == null -> directPlan
            receiver.prefersDirectPlay && !settings.castAlwaysConvert -> directPlan
            else -> CastDeliveryPlanner.plan(probe, capabilities, request.sourceReachableByReceiver).let { planned ->
                val converts = planned.mode == CastDeliveryMode.REMUX || planned.mode == CastDeliveryMode.TRANSCODE
                if (receiver.prefersDirectPlay && converts && !confirmConversion(settings, planned)) {
                    directPlan
                } else {
                    planned
                }
            }
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
                // A converted M3U8 stream already starts at the resume point and cannot be
                // seeked, so the renderer must not be asked to seek again.
                startPositionMs = if (hlsAsTs) 0L else request.startPositionMs,
                durationMs = probe?.durationMs,
                isLive = probe?.isLive == true && probe.durationMs == null,
                subtitles = request.subtitles,
            ),
        )

        return result.fold(
            onSuccess = {
                _status.value = CastDeliveryStatus.Playing(if (hlsAsTs) CastDeliveryMode.REMUX else plan.mode)
                liveJob?.let(::watchLiveJob)
                Result.success(Unit)
            },
            onFailure = { error -> fail(error.message ?: "The TV refused the stream") },
        )
    }

    actual fun cancel() {
        // Cancelling the delivery coroutine itself, not only its resources: a cast still
        // probing, asking or loading would otherwise carry on afterwards and publish again. The
        // generation bump stops any cast still waiting for the lock as well.
        castGeneration.incrementAndGet()
        activeDelivery?.cancel()
        processor?.cancel()
        releasePrevious()
        _status.value = CastDeliveryStatus.Idle
    }

    /**
     * Reports a live remux that dies after the renderer has started playing it. Without this
     * the stream just ends early on the TV while the app still says it is playing.
     */
    private fun watchLiveJob(job: LiveRemuxJob) {
        watchScope.launch {
            while (!job.isFinished) delay(LIVE_JOB_WATCH_MS)
            val failure = job.failure ?: return@launch
            deliveryLock.withLock {
                // Only if this job still belongs to the current cast.
                if (liveJob !== job) return@withLock
                Log.w(TAG, "M3U8 conversion stopped during playback: $failure")
                fail("The M3U8 conversion stopped during playback")
            }
        }
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
     * Whether to go ahead with converting a file for a DLNA renderer under "Always convert":
     * always, or after asking with the reasons the planner found.
     */
    private suspend fun confirmConversion(settings: NuvioEnhancedSettingsUiState, plan: CastDeliveryPlan): Boolean {
        if (!settings.castAlwaysConvertAsk) return true
        val reasons = plan.reasons.joinToString(", ") { describeCastIncompatibility(it) }
        val action = if (plan.mode == CastDeliveryMode.TRANSCODE) "converted" else "repackaged"
        return CastConversionPrompt.ask(
            title = "Convert this file?",
            message = buildString {
                append("Your TV may not play this file as it is")
                if (reasons.isNotEmpty()) append(" ($reasons)")
                append(". It can be $action on your phone first, but the whole file has to be done before it plays.")
            },
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
        val inputs = withContext(Dispatchers.IO) { selectHlsInputs(request.url, request.headers) }
        val job = startHlsToTsRemux(inputs, request.headers, request.startPositionMs, output)
            ?: return Result.failure(IllegalStateException("This build cannot convert M3U8 streams"))
        liveJob = job

        val started = try {
            withTimeoutOrNull(HLS_START_TIMEOUT_MS) {
                // A full head start when the stream delivers it quickly (any VOD); otherwise a
                // small one plus a few seconds, which a low-bitrate live stream produces only in
                // real time and might never reach 512 KB of within the timeout.
                val begin = System.currentTimeMillis()
                while (!job.isFinished) {
                    val written = output.length()
                    if (written >= HLS_HEAD_START_BYTES) break
                    val waited = System.currentTimeMillis() - begin
                    if (written >= HLS_MIN_START_BYTES && waited >= HLS_MIN_START_WAIT_MS) break
                    delay(HLS_START_POLL_MS)
                }
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
        // Nothing failed casts leave behind is still wanted, and a live remux left running would
        // keep downloading the whole stream into the cache.
        releasePrevious()
        _status.value = CastDeliveryStatus.Failed(message)
        return Result.failure(IllegalStateException(message))
    }

    private fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "")
}
