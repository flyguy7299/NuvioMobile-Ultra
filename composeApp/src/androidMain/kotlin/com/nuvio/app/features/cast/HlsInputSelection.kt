package com.nuvio.app.features.cast

import java.net.HttpURLConnection
import java.net.URL

/** The playlist(s) FFmpeg should read: one variant, plus its audio rendition when separate. */
internal data class HlsInputs(val video: String, val audio: String? = null)

/**
 * Picks the single variant to remux from an HLS master playlist.
 *
 * Handed a master playlist, FFmpeg's HLS demuxer opens every variant and every audio rendition
 * while reading the header — the init segment and first media segment of each — before it drops
 * the ones it won't use, which made the stream slow to start. Choosing here means FFmpeg opens
 * exactly the two playlists it needs. The highest-bandwidth variant wins; its audio comes from
 * the rendition group it names, preferring that group's DEFAULT entry.
 *
 * Falls back to [url] itself when it isn't a master playlist or can't be read.
 */
internal fun selectHlsInputs(url: String, headers: Map<String, String>): HlsInputs {
    val (text, finalUrl) = runCatching { fetchText(url, headers) }.getOrNull() ?: return HlsInputs(url)
    // Relative URIs are relative to where the playlist actually came from, after redirects.
    return parseHlsMaster(text, finalUrl) ?: HlsInputs(url)
}

internal fun parseHlsMaster(text: String, baseUrl: String): HlsInputs? {
    val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

    data class Variant(val bandwidth: Long, val audioGroup: String?, val uri: String)
    val variants = mutableListOf<Variant>()
    lines.forEachIndexed { index, line ->
        if (!line.startsWith("#EXT-X-STREAM-INF:")) return@forEachIndexed
        val attributes = parseAttributes(line.substringAfter(':'))
        val uri = lines.drop(index + 1).firstOrNull { !it.startsWith("#") } ?: return@forEachIndexed
        variants += Variant(
            bandwidth = attributes["BANDWIDTH"]?.toLongOrNull() ?: 0L,
            audioGroup = attributes["AUDIO"],
            uri = uri,
        )
    }
    val best = variants.maxByOrNull { it.bandwidth } ?: return null

    val audio = best.audioGroup?.let { group ->
        val renditions = lines
            .filter { it.startsWith("#EXT-X-MEDIA:") }
            .map { parseAttributes(it.substringAfter(':')) }
            .filter { it["TYPE"] == "AUDIO" && it["GROUP-ID"] == group && it["URI"] != null }
        (renditions.firstOrNull { it["DEFAULT"] == "YES" } ?: renditions.firstOrNull())?.get("URI")
    }

    return HlsInputs(
        video = resolve(baseUrl, best.uri),
        audio = audio?.let { resolve(baseUrl, it) },
    )
}

/** `KEY=value,KEY="quoted, value"` attribute lists, with the quotes removed. */
private fun parseAttributes(list: String): Map<String, String> =
    Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(list).associate { match ->
        match.groupValues[1] to match.groupValues[2].removeSurrounding("\"")
    }

private fun resolve(base: String, reference: String): String = URL(URL(base), reference).toString()

/** The body, and the URL it came from once redirects were followed. */
private fun fetchText(url: String, headers: Map<String, String>): Pair<String, String> {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = FETCH_TIMEOUT_MS
        readTimeout = FETCH_TIMEOUT_MS
        instanceFollowRedirects = true
        headers.forEach { (key, value) -> setRequestProperty(key, value) }
    }
    return try {
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        text to connection.url.toString()
    } finally {
        connection.disconnect()
    }
}

private const val FETCH_TIMEOUT_MS = 10_000
