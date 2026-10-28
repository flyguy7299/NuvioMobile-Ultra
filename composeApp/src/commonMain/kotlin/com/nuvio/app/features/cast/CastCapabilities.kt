package com.nuvio.app.features.cast

/**
 * What this build of the app can do when casting, for shared UI that cannot see the Android
 * build flavour. Set once at startup by the platform; false until then and on platforms that
 * never set it.
 */
object CastCapabilities {
    /** FFmpeg is bundled, so live remuxing ("Convert M3U8", subtitles inside the video) works. */
    var liveRemux: Boolean = false
}
