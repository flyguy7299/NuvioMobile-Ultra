package com.nuvio.app.features.cast

import android.content.Context

/**
 * Stand-in used when the build does not bundle FFmpeg.
 *
 * Returning null makes the caller fall back to the Media3 Transformer processor, which
 * handles the common cases with hardware encoders but cannot decode formats the handset
 * itself cannot. Build with `-Pnuvio.android.ffmpeg=true`, and the FFmpegKit AAR in
 * composeApp/libs, to swap in the FFmpeg implementation from src/androidFfmpeg.
 */
internal fun createFFmpegCastProcessor(context: Context): CastMediaProcessor? = null

/**
 * Null means "no FFmpeg in this build". The converter's capability probe reads this as "Media3
 * does everything", which is exactly right here.
 */
internal fun ffmpegCapabilityReport(): FFmpegCapabilityReport? = null

/** False: no live remuxing in this build. */
internal const val FFMPEG_BUNDLED = false

/** Null: muxing a subtitle track into the video needs FFmpeg. */
internal fun startSubtitleMuxToMkv(
    videoUrl: String,
    headers: Map<String, String>,
    startPositionMs: Long,
    subtitleFile: java.io.File,
    subtitleLanguage: String?,
    output: java.io.File,
): LiveRemuxJob? = null

/** Null: merging HLS renditions into one TS stream needs FFmpeg. */
internal fun startHlsToTsRemux(
    inputs: HlsInputs,
    headers: Map<String, String>,
    startPositionMs: Long,
    output: java.io.File,
): LiveRemuxJob? = null
