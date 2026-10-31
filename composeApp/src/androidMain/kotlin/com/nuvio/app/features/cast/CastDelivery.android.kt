package com.nuvio.app.features.cast

import android.content.Context
import android.util.Log
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastMediaProbe
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.cast.ui.describeCastIncompatibility
import com.nuvio.app.features.player.sanitizePlaybackHeaders
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
    private const val MKV_CONTENT_TYPE = "video/x-matroska"
    private const val MP4_CONTENT_TYPE = "video/mp4"
    private const val SRT_CONTENT_TYPE = "application/x-subrip"

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
    // Lists: one cast can serve several things at once (a video and its subtitle).
    private val publishedIds = mutableListOf<String>()
    private val workingFiles = mutableListOf<File>()
    private var liveJob: LiveRemuxJob? = null
    /** What happened to this cast's subtitle, shown once it plays; null when there is nothing to say. */
    private var subtitleNote: String? = null

    /** One delivery at a time: they share every field above. */
    private val deliveryLock = Mutex()
    /**
     * Guards the resource lists and the final status against cancel(), which runs outside
     * [deliveryLock] while a delivery may still be unwinding.
     */
    private val stateLock = Any()
    @Volatile private var activeDelivery: Job? = null
    private val castGeneration = AtomicLong()
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow<CastDeliveryStatus>(CastDeliveryStatus.Idle)
    actual val status: StateFlow<CastDeliveryStatus> = _status.asStateFlow()

    /** Android-only entry point; call alongside [CastPlatform.initialize]. */
    fun initialize(context: Context) {
        // Nothing is casting yet, so anything in cast/ was left by a process that was killed
        // mid-cast — possibly a multi-gigabyte live remux — and would otherwise stay forever.
        // Only files older than this moment are removed, and the moment is taken before casting
        // becomes possible (appContext is set), so a cast's own file is never older than it.
        val cutoff = System.currentTimeMillis()
        CastCapabilities.liveRemux = FFMPEG_BUNDLED
        appContext = context.applicationContext
        val leftovers = File(context.cacheDir, "cast")
        Thread({
            leftovers.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { runCatching { it.delete() } }
        }, "cast-cache-purge").start()
    }

    actual suspend fun cast(request: CastStreamRequest): Result<Unit> {
        // Only the newest request may run: a cast still queued for the lock when a newer one (or
        // cancel()) arrives gives up instead of running after it. Claimed first, so a call can't
        // be overtaken between entering and claiming.
        val generation = castGeneration.incrementAndGet()
        // A superseded cast can still be unwinding from a blocking call (a SOAP request, the
        // probe), so it is cancelled and waited out rather than left to release or overwrite the
        // resources this one is about to create.
        activeDelivery?.cancel()
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
                synchronized(stateLock) {
                    releasePrevious()
                    _status.value = CastDeliveryStatus.Idle
                }
                throw cancelled
            } catch (error: Exception) {
                // Anything unexpected fails the cast; thrown on, it would crash the app. The class
                // only, as elsewhere: a message can carry the stream URL.
                Log.w(TAG, "Cast failed: ${error::class.simpleName}")
                fail("Casting failed (${error::class.simpleName})")
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
        subtitleNote = null

        val capabilities = receiver.capabilities
        val settings = NuvioEnhancedSettingsRepository.run {
            ensureLoaded()
            uiState.value
        }

        // --- Probe ---------------------------------------------------------------------
        setProgress(CastDeliveryStatus.Probing)
        val probeOrNull: suspend () -> CastMediaProbe? = {
            probeCastMedia(request.url, request.headers).getOrElse { error ->
                // A probe failure is not fatal. The receiver may well handle the stream, so fall
                // back to an optimistic direct play rather than refusing to cast at all.
                Log.w(TAG, "Probe failed, attempting direct play", error)
                null
            }
        }
        // "Burn subtitles into the video" re-encodes with the subtitle drawn on, for renderers that
        // show none at all. Only meaningful for DLNA, and only with a subtitle selected.
        val burnRequested = receiver.prefersDirectPlay && settings.castBurnSubtitles && request.subtitles.isNotEmpty()
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
        // Not for HLS (Transformer's HLS input isn't relied on; those keep M3U8 -> TS with the
        // subtitle beside it) or live streams.
        val burnSubtitles = burnRequested && !dlnaHls && probe?.isLive != true
        if (burnRequested && !burnSubtitles) {
            subtitleNote = if (dlnaHls) {
                "Subtitles can't be burned into M3U8 streams; sent separately"
            } else {
                "Subtitles can't be burned into live streams; sent separately"
            }
        } else if (receiver.prefersDirectPlay && settings.castBurnSubtitles && request.subtitles.isEmpty()) {
            subtitleNote = "No addon subtitle selected, so none was burned in"
        }
        val plan = when {
            // A burn-in needs no plan of its own; this one is only the fallback if it fails, so
            // nothing is asked now.
            burnSubtitles && (probe == null || !settings.castAlwaysConvert) -> directPlan
            burnSubtitles -> CastDeliveryPlanner.plan(probe!!, capabilities, request.sourceReachableByReceiver)
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

        // --- Subtitle -------------------------------------------------------------------
        // DLNA only: the selected addon subtitle is converted to SRT and served from the phone,
        // either beside the video or muxed into it ("Put subtitles inside the video"). Chromecast
        // gets none, as before: its receiver needs WebVTT with CORS, which isn't done here.
        val muxSubtitles = !burnSubtitles && receiver.prefersDirectPlay && settings.castEmbedSubtitles && FFMPEG_BUNDLED &&
            !hlsAsTs && plan.mode == CastDeliveryMode.DIRECT && probe?.isLive != true &&
            request.subtitles.isNotEmpty()
        // A stream that starts at the resume point (a live remux) needs its subtitle times shifted
        // the same way; the renderer seeks everything else itself.
        var subtitleFile = if (receiver.prefersDirectPlay) {
            val startsAtResume = hlsAsTs || muxSubtitles || burnSubtitles
            prepareSubtitle(context, request, originMs = if (startsAtResume) request.startPositionMs else 0L)
        } else {
            null
        }

        // Muxing is optional, so a failure falls back to the video as is with the subtitle beside
        // it, rather than failing a cast that would otherwise play.
        val muxedUrl = subtitleFile?.takeIf { muxSubtitles }?.let { muxSource ->
            val language = request.subtitles.first().language
            startLiveStream(context, ".mkv", MKV_CONTENT_TYPE, "video with subtitles") { output ->
                startSubtitleMuxToMkv(request.url, request.headers, request.startPositionMs, muxSource, language, output)
            }.getOrElse { error ->
                Log.w(TAG, "Could not put the subtitles inside the video, sending them separately: ${error.message}")
                liveJob?.cancel()
                liveJob = null
                // The muxed copy was shifted to the resume point; a renderer that seeks itself
                // needs the original times.
                subtitleFile = prepareSubtitle(context, request, originMs = 0L)
                null
            }
        }

        // Optional in the same way: a failure falls back to the video with the subtitle beside it.
        val burnedUrl = subtitleFile?.takeIf { burnSubtitles }?.let { burnSource ->
            // A re-encode, so the banner says converting rather than repackaging.
            startLiveStream(context, ".mp4", MP4_CONTENT_TYPE, "video with burned-in subtitles", CastDeliveryMode.TRANSCODE) { output ->
                startSubtitleBurn(context, request.url, request.headers, request.startPositionMs, burnSource, output)
            }.getOrElse { error ->
                Log.w(TAG, "Could not burn the subtitles in, sending them separately: ${error.message}")
                // BurnJob failures are error codes only, never URLs, so they can be shown.
                // No job at all means startSubtitleBurn couldn't read the prepared subtitle.
                val detail = liveJob?.let { it.failure ?: error.message } ?: "subtitle unreadable"
                liveJob?.cancel()
                liveJob = null
                subtitleFile = prepareSubtitle(context, request, originMs = 0L)
                // Set after the retry, which would otherwise replace the more useful burn error.
                subtitleNote = "Couldn't burn subtitles in ($detail); " +
                    if (subtitleFile != null) "sent separately" else "cast without subtitles"
                null
            }
        }

        // --- Produce a playable source -------------------------------------------------
        val contentUrl: String
        val contentType: String
        val subtitlesMuxed = muxedUrl != null
        val subtitlesBurned = burnedUrl != null
        val liveRemux = hlsAsTs || subtitlesMuxed || subtitlesBurned

        if (hlsAsTs) {
            contentType = TS_CONTENT_TYPE
            val inputs = withContext(Dispatchers.IO) { selectHlsInputs(request.url, request.headers) }
            contentUrl = startLiveStream(context, ".ts", contentType, "M3U8 stream") { output ->
                startHlsToTsRemux(inputs, request.headers, request.startPositionMs, output)
            }.getOrElse { error ->
                return fail(error.message ?: "Could not convert this M3U8 stream for casting")
            }
        } else if (muxedUrl != null) {
            contentType = MKV_CONTENT_TYPE
            contentUrl = muxedUrl
        } else if (burnedUrl != null) {
            contentType = MP4_CONTENT_TYPE
            contentUrl = burnedUrl
        } else if (plan.mode == CastDeliveryMode.DIRECT) {
            contentType = contentTypeFor(plan.targetContainer)
            contentUrl = if (plan.requiresLocalServer) {
                publish(CastLocalServer.Payload.Proxy(request.url, contentType, request.headers))
                    ?: return fail("Phone is not on a network the TV can reach")
            } else {
                request.url
            }
        } else {
            setProgress(CastDeliveryStatus.Preparing(plan.mode, -1, plan.reasons))

            val engine = processor ?: castMediaProcessor(context).also { processor = it }
            val output = newWorkingFile(context, ".mp4")

            val produced = engine.process(
                sourceUrl = request.url,
                plan = plan,
                output = output,
                durationMs = probe?.durationMs,
                headers = request.headers,
            ) { progress ->
                setProgress(CastDeliveryStatus.Preparing(plan.mode, progress.percent, plan.reasons))
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
            contentUrl = publish(CastLocalServer.Payload.LocalFile(produced, contentType))
                ?: return fail("Phone is not on a network the TV can reach")
        }

        // Beside the video unless it went inside it. Served under a ".srt" path, which is how
        // renderers and the DIDL builder tell the subtitle format.
        val externalSubtitle = subtitleFile?.takeUnless { subtitlesMuxed || subtitlesBurned }
        val castSubtitles = if (externalSubtitle != null) {
            publish(CastLocalServer.Payload.LocalFile(externalSubtitle, SRT_CONTENT_TYPE), suffix = ".srt")
                ?.let { url -> listOf(request.subtitles.first().copy(url = url)) }
                ?: emptyList<CastSubtitleTrack>().also { subtitleNote = "Subtitle couldn't be served; cast without subtitles" }
        } else {
            emptyList()
        }

        // --- Hand off ------------------------------------------------------------------
        val result = receiver.deliver(
            CastMediaRequest(
                contentUrl = contentUrl,
                contentType = contentType,
                title = request.title,
                subtitle = request.subtitle,
                posterUrl = request.posterUrl,
                // A live remux already starts at the resume point and cannot be seeked, so the
                // renderer must not be asked to seek again.
                startPositionMs = if (liveRemux) 0L else request.startPositionMs,
                // Likewise its length is only what is left from there.
                durationMs = probe?.durationMs?.let { full ->
                    if (liveRemux) (full - request.startPositionMs.coerceAtLeast(0)).coerceAtLeast(0) else full
                },
                isLive = probe?.isLive == true && probe.durationMs == null,
                subtitles = castSubtitles,
            ),
        )

        return result.fold(
            onSuccess = {
                synchronized(stateLock) {
                    // As in fail(): cancelled meanwhile, the status is already Idle.
                    if (activeDelivery?.isCancelled != true) {
                        _status.value = CastDeliveryStatus.Playing(
                            mode = when {
                                subtitlesBurned -> CastDeliveryMode.TRANSCODE
                                liveRemux -> CastDeliveryMode.REMUX
                                else -> plan.mode
                            },
                            note = if (subtitlesBurned) "Subtitles burned in; seeking is off while casting" else subtitleNote,
                        )
                        liveJob?.let(::watchLiveJob)
                    }
                }
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
        // Under stateLock, so a live job's watcher can't report a failure after this.
        synchronized(stateLock) {
            releasePrevious()
            _status.value = CastDeliveryStatus.Idle
        }
    }

    /**
     * Reports a live remux that dies after the renderer has started playing it. Without this
     * the stream just ends early on the TV while the app still says it is playing.
     */
    private fun watchLiveJob(job: LiveRemuxJob) {
        watchScope.launch {
            while (!job.isFinished) delay(LIVE_JOB_WATCH_MS)
            val failure = job.failure ?: return@launch
            // cancel() doesn't take deliveryLock (it isn't suspending), so the check and the
            // failure are made atomic against it with stateLock.
            deliveryLock.withLock {
                synchronized(stateLock) {
                    // Only if this job still belongs to the current cast.
                    if (liveJob !== job) return@withLock
                    Log.w(TAG, "Live remux stopped during playback: $failure")
                    fail("The conversion stopped during playback ($failure)")
                }
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
     * Fetches the selected addon subtitle and writes it as SRT for a DLNA renderer, or returns
     * null. A subtitle that can't be fetched or read never fails the cast, it just isn't sent:
     * handing the renderer the original URL instead is no good when that URL needs headers.
     */
    private suspend fun prepareSubtitle(context: Context, request: CastStreamRequest, originMs: Long): File? {
        val track = request.subtitles.firstOrNull() ?: return null
        return try {
            val text = httpGetTextWithHeaders(track.url, subtitleHeaders(track.url, request))
            // Off the main thread, which cast() is called from: a long file takes a moment.
            val srt = withContext(Dispatchers.Default) {
                CastSubtitleConverter.toSrt(text, track.url, request.subtitleDelayMs.toLong(), originMs)
            }
            if (srt == null) {
                Log.w(TAG, "Subtitle format not recognised, casting without it")
                subtitleNote = "Subtitle format not recognised; cast without subtitles"
                return null
            }
            val file = newWorkingFile(context, ".srt")
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.writeText(srt)
            }
            file
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not fetch the subtitle, casting without it", error)
            // The class only: the message can carry the subtitle URL.
            subtitleNote = "Subtitle couldn't be downloaded (${error::class.simpleName}); cast without subtitles"
            null
        }
    }

    /**
     * The stream's headers can hold its credentials (cookies, tokens), so they only go to a
     * subtitle on the stream's own host. Any other host gets the User-Agent alone.
     */
    private fun subtitleHeaders(subtitleUrl: String, request: CastStreamRequest): Map<String, String> {
        val headers = sanitizePlaybackHeaders(request.headers)
        val host = { url: String -> runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() }
        val sameHost = host(subtitleUrl)?.let { it == host(request.url) } == true
        return if (sameHost) headers else headers.filterKeys { it.equals("User-Agent", ignoreCase = true) }
    }

    /**
     * Starts a live remux into a new working file and publishes it while it is still being
     * written, once there is enough of a head start for the renderer's first read. Returns the
     * URL to hand the renderer. [what] names the stream in error messages.
     */
    private suspend fun startLiveStream(
        context: Context,
        extension: String,
        contentType: String,
        what: String,
        mode: CastDeliveryMode = CastDeliveryMode.REMUX,
        start: (File) -> LiveRemuxJob?,
    ): Result<String> {
        setProgress(CastDeliveryStatus.Preparing(mode, -1, emptyList()))
        val output = newWorkingFile(context, extension)
        val job = start(output)
            ?: return Result.failure(IllegalStateException("This build cannot convert the $what"))
        synchronized(stateLock) { liveJob = job }

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
            Log.w(TAG, "Live remux failed: $detail")
            return Result.failure(IllegalStateException("Could not prepare the $what for casting"))
        }
        if (started == null) {
            job.cancel()
            return Result.failure(IllegalStateException("The $what did not start in time"))
        }

        val url = publish(CastLocalServer.Payload.GrowingFile(output, contentType, job))
            ?: return Result.failure(IllegalStateException("Phone is not on a network the TV can reach"))
        return Result.success(url)
    }

    /** Publishes [payload] under a new route this cast owns, so [releasePrevious] removes it. */
    private fun publish(payload: CastLocalServer.Payload, suffix: String = ""): String? = synchronized(stateLock) {
        val id = newId() + suffix
        publishedIds += id
        server.publish(id, payload)
    }

    /** A new file in the cast cache this cast owns, so [releasePrevious] deletes it. */
    private fun newWorkingFile(context: Context, extension: String): File = synchronized(stateLock) {
        File(context.cacheDir, "cast/${newId()}$extension").also { workingFiles += it }
    }

    private fun releasePrevious() = synchronized(stateLock) {
        liveJob?.cancel()
        liveJob = null
        publishedIds.forEach(server::unpublish)
        publishedIds.clear()
        workingFiles.forEach { file -> runCatching { if (file.exists()) file.delete() } }
        workingFiles.clear()
    }

    /**
     * A status on the way to playing. Dropped unless a delivery is still running: a converter's
     * progress callback can arrive after cancel() set Idle, or after the cast ended.
     */
    private fun setProgress(status: CastDeliveryStatus) = synchronized(stateLock) {
        if (activeDelivery?.isActive == true) _status.value = status
    }

    private fun fail(message: String): Result<Unit> {
        // Nothing failed casts leave behind is still wanted, and a live remux left running would
        // keep downloading the whole stream into the cache.
        synchronized(stateLock) {
            releasePrevious()
            // A delivery cancelled meanwhile (Stop casting, or a newer cast) has already set the
            // status; a late failure must not replace it.
            if (activeDelivery?.isCancelled != true) _status.value = CastDeliveryStatus.Failed(message)
        }
        return Result.failure(IllegalStateException(message))
    }

    private fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "")
}
