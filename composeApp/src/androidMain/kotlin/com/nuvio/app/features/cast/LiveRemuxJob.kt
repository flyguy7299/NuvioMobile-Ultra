package com.nuvio.app.features.cast

/**
 * A repackaging job whose output is served while it is still being written.
 *
 * Unlike [CastMediaProcessor.process], which returns only once the whole file exists, this is
 * for sources a renderer cannot take at all — an M3U8 playlist on a TV that only plays single
 * files — where waiting for the end is not an option. See [startHlsToTsRemux].
 */
internal interface LiveRemuxJob {
    /** True once the job has stopped, whether it completed or failed. */
    val isFinished: Boolean

    /** Why the job failed, or null while it is running or after it completed. */
    val failure: String?

    fun cancel()
}
