package com.nuvio.app.features.cast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppFragmentedMp4Muxer
import androidx.media3.transformer.Transformer
import java.io.File

private const val TAG = "SubtitleBurner"
/** How much video each written piece holds: the renderer waits about this long to start. */
private const val FRAGMENT_DURATION_MS = 2_000L

/**
 * Starts re-encoding [url] with the subtitle in [subtitleSrt] drawn onto the picture, writing a
 * fragmented MP4 to [output] that can be served while it grows. Returns null when the subtitle
 * can't be read.
 *
 * For renderers that show no subtitles at all, external or embedded. FFmpeg can't do this here
 * (no libass, no video encoder in the bundled build), so it is Media3 Transformer on the phone's
 * hardware encoder. Only the subtitle is added: everything else is left to Transformer's defaults,
 * which keep the source's video codec and HDR where the phone can encode them, and copy the audio
 * unless the MP4 can't hold it (then it becomes AAC, channels kept). Fragmented MP4, because a
 * normal MP4 writes its index at the end, after the renderer has started reading.
 *
 * The output starts at [startPositionMs] and the subtitle must already be shifted to it: Transformer
 * hands effects timestamps relative to the clip start.
 */
@OptIn(UnstableApi::class)
internal fun startSubtitleBurn(
    context: Context,
    url: String,
    headers: Map<String, String>,
    startPositionMs: Long,
    subtitleSrt: File,
    output: File,
): LiveRemuxJob? {
    val cues = runCatching { CastSubtitleConverter.parse(subtitleSrt.readText(), subtitleSrt.name) }
        .getOrNull()
        ?.sortedBy { it.startMs }
        ?: return null
    output.parentFile?.mkdirs()
    if (output.exists()) output.delete()

    val job = BurnJob()
    // Transformer must be built, started and cancelled on one looper thread, and reports there.
    val thread = HandlerThread("cast-subtitle-burn").apply { start() }
    val handler = Handler(thread.looper)
    job.handler = handler
    handler.post {
        try {
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    job.finish(null)
                }

                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    Log.w(TAG, "Subtitle burn failed", exception)
                    // Shown to the user, so codes and class names only: messages can carry the URL.
                    job.finish(
                        listOfNotNull(exception.errorCodeName, exception.cause?.let { it::class.simpleName })
                            .joinToString(", "),
                    )
                }
            }
            val builder = Transformer.Builder(context)
                .setLooper(thread.looper)
                .addListener(listener)
                .setMuxerFactory(InAppFragmentedMp4Muxer.Factory(FRAGMENT_DURATION_MS))
            // Same reason as Media3CastMediaProcessor: the stock loader has nowhere to put headers.
            if (headers.isNotEmpty()) {
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(headers)
                    .setAllowCrossProtocolRedirects(true)
                builder.setAssetLoaderFactory(
                    DefaultAssetLoaderFactory(
                        context,
                        DefaultDecoderFactory.Builder(context).build(),
                        Clock.DEFAULT,
                        DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory),
                        DataSourceBitmapLoader(context),
                    ),
                )
            }

            val videoEffects = listOf(OverlayEffect(listOf(SubtitleOverlay(cues))))

            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startPositionMs.coerceAtLeast(0)).build(),
                )
                .build()
            val item = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(emptyList(), videoEffects))
                .build()
            val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build()).build()

            val transformer = builder.build()
            job.transformer = transformer
            transformer.start(composition, output.absolutePath)
        } catch (error: Throwable) {
            Log.e(TAG, "Could not start the subtitle burn", error)
            job.finish(error.javaClass.simpleName)
        }
    }
    return job
}

@OptIn(UnstableApi::class)
internal class BurnJob : LiveRemuxJob {
    @Volatile var handler: Handler? = null
    // Only touched on the job's own looper thread.
    var transformer: Transformer? = null
    @Volatile override var isFinished: Boolean = false
    @Volatile override var failure: String? = null

    /** On the job's thread. Records the outcome and stops the thread. */
    fun finish(failure: String?) {
        if (isFinished) return
        this.failure = failure
        isFinished = true
        transformer = null
        handler?.looper?.quitSafely()
    }

    override fun cancel() {
        handler?.post {
            if (isFinished) return@post
            runCatching { transformer?.cancel() }
            finish(null)
        }
    }
}

/**
 * Draws the subtitle active at each frame, bottom centre. One bitmap per cue, rendered when the cue
 * first shows: BitmapOverlay re-uploads only when the bitmap it is given changes.
 */
@OptIn(UnstableApi::class)
private class SubtitleOverlay(private val cues: List<CastSubtitleConverter.Cue>) : BitmapOverlay() {

    private var frameSize = Size(1920, 1080)
    private var shownIndex = -1
    private var shownBitmap: Bitmap = EMPTY

    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, -0.9f)
        .setOverlayFrameAnchor(0f, -1f)
        .build()

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        if (videoSize != frameSize) {
            frameSize = videoSize
            shownIndex = -1
            shownBitmap = EMPTY
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val index = activeCue(presentationTimeUs / 1000)
        if (index != shownIndex) {
            shownIndex = index
            shownBitmap = if (index < 0) EMPTY else render(cues[index].text)
        }
        return shownBitmap
    }

    /** The cue showing at [timeMs], or -1. Cues are flattened, so at most one is active. */
    private fun activeCue(timeMs: Long): Int {
        var low = 0
        var high = cues.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cue = cues[mid]
            when {
                timeMs < cue.startMs -> high = mid - 1
                timeMs >= cue.endMs -> low = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    private fun render(text: String): Bitmap {
        val textSize = frameSize.height * 0.055f
        val maxWidth = (frameSize.width * 0.9f).toInt().coerceAtLeast(1)
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
        }
        val stroke = TextPaint(fill).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = textSize * 0.12f
            strokeJoin = Paint.Join.ROUND
        }
        val fillLayout = layout(text, fill, maxWidth)
        val strokeLayout = layout(text, stroke, maxWidth)
        val pad = stroke.strokeWidth.toInt() + 2
        val width = (0 until fillLayout.lineCount).maxOfOrNull { fillLayout.getLineWidth(it) }?.toInt() ?: 0
        if (width <= 0 || fillLayout.height <= 0) return EMPTY
        val bitmap = Bitmap.createBitmap(width + pad * 2, fillLayout.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Layouts are maxWidth wide and centre each line; shift so the widest line starts at pad.
        canvas.translate(pad - (maxWidth - width) / 2f, pad.toFloat())
        strokeLayout.draw(canvas)
        fillLayout.draw(canvas)
        return bitmap
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

    private companion object {
        val EMPTY: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }
}
