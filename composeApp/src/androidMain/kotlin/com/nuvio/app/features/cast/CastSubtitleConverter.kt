package com.nuvio.app.features.cast

/**
 * Turns an addon subtitle (SRT, WebVTT or ASS/SSA) into a plain SRT a DLNA renderer can show.
 *
 * TVs read SRT far more reliably than WebVTT and almost never ASS, and many render only one cue
 * at a time. That last point matters for "dual subtitle" addons, whose output may be two
 * overlapping cues per line (one per language): the timeline is cut at every start and end, and
 * each slice becomes one cue joining the text of everything active in it, so both languages show.
 *
 * `PlayerSubtitleCueParser` cannot be reused: it keeps only start times.
 */
internal object CastSubtitleConverter {

    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /**
     * Returns SRT text, or null when the format isn't recognised or no cue survives.
     *
     * [delayMs] is the player's subtitle delay (positive shows subtitles later). [originMs] is
     * subtracted from every time, for a stream that starts at a resume point rather than at 0.
     */
    fun toSrt(text: String, sourceUrl: String?, delayMs: Long = 0, originMs: Long = 0): String? {
        val cues = parse(text, sourceUrl) ?: return null
        val shifted = cues.mapNotNull { cue ->
            val start = cue.startMs + delayMs - originMs
            val end = cue.endMs + delayMs - originMs
            if (end <= 0 || end <= start) null else Cue(start.coerceAtLeast(0), end, cue.text)
        }
        val flat = flatten(shifted)
        if (flat.isEmpty()) return null
        return buildString {
            flat.forEachIndexed { index, cue ->
                append(index + 1).append('\n')
                append(timestamp(cue.startMs)).append(" --> ").append(timestamp(cue.endMs)).append('\n')
                append(cue.text).append("\n\n")
            }
        }
    }

    internal fun parse(text: String, sourceUrl: String?): List<Cue>? {
        val normalized = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return null
        val path = sourceUrl?.substringBefore('?')?.substringBefore('#')?.lowercase().orEmpty()
        val cues = when {
            normalized.startsWith("WEBVTT") || path.endsWith(".vtt") -> parseBlocks(normalized)
            normalized.contains("[Events]", ignoreCase = true) || path.endsWith(".ass") || path.endsWith(".ssa") ->
                parseAss(normalized)
            TIMING.containsMatchIn(normalized) -> parseBlocks(normalized)
            else -> return null
        }
        return cues.takeIf { it.isNotEmpty() }
    }

    /** SRT and WebVTT share the shape: blank-line separated blocks with a `start --> end` line. */
    private fun parseBlocks(text: String): List<Cue> =
        text.split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines = block.lines()
            val timingIndex = lines.indexOfFirst { TIMING.containsMatchIn(it) }
            if (timingIndex < 0) return@mapNotNull null
            val match = TIMING.find(lines[timingIndex]) ?: return@mapNotNull null
            val start = parseTime(match.groupValues[1]) ?: return@mapNotNull null
            val end = parseTime(match.groupValues[2]) ?: return@mapNotNull null
            val body = lines.drop(timingIndex + 1)
                .joinToString("\n") { it.replace(MARKUP_TAG, "").trim() }
                .trim()
            if (body.isEmpty()) null else Cue(start, end, unescape(body))
        }

    private fun parseAss(text: String): List<Cue> {
        var inEvents = false
        var format: List<String>? = null
        val cues = mutableListOf<Cue>()
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("[") -> inEvents = line.equals("[Events]", ignoreCase = true)
                !inEvents -> Unit
                line.startsWith("Format:", ignoreCase = true) ->
                    format = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                line.startsWith("Dialogue:", ignoreCase = true) -> {
                    val fields = format ?: DEFAULT_ASS_FORMAT
                    val startIndex = fields.indexOf("start")
                    val endIndex = fields.indexOf("end")
                    val textIndex = fields.indexOf("text")
                    if (startIndex < 0 || endIndex < 0 || textIndex < 0) continue
                    // Text is the last field and may itself contain commas.
                    val values = line.substringAfter(':').split(',', limit = fields.size).map { it.trim() }
                    if (values.size < fields.size) continue
                    val start = parseTime(values[startIndex]) ?: continue
                    val end = parseTime(values[endIndex]) ?: continue
                    val body = values[textIndex]
                        .replace(ASS_OVERRIDE, "")
                        .replace("\\N", "\n")
                        .replace("\\n", "\n")
                        .replace("\\h", " ")
                        .trim()
                    if (body.isNotEmpty()) cues += Cue(start, end, body)
                }
            }
        }
        return cues
    }

    /** Cuts the timeline at every boundary so overlapping cues become one cue per slice. */
    private fun flatten(cues: List<Cue>): List<Cue> {
        if (cues.isEmpty()) return cues
        val boundaries = cues.flatMap { listOf(it.startMs, it.endMs) }.distinct().sorted()
        // A sweep over the boundaries, keeping the cues showing between them, instead of scanning
        // every cue for every slice: that was slow on a long dual-language file.
        val startingAt = cues.indices.groupBy { cues[it].startMs }
        val endingAt = cues.indices.groupBy { cues[it].endMs }
        // Source order, not time order, so a dual-language file keeps each language on the same
        // line even when one of its cues ends sooner than the other.
        val showing = java.util.TreeSet<Int>()
        val result = mutableListOf<Cue>()
        for (i in 0 until boundaries.size - 1) {
            val from = boundaries[i]
            val to = boundaries[i + 1]
            endingAt[from]?.let(showing::removeAll)
            // A zero-length cue never shows.
            startingAt[from]?.filter { cues[it].endMs > from }?.let(showing::addAll)
            val text = showing.map { cues[it].text }
                .distinct()
                .joinToString("\n")
            if (text.isEmpty()) continue
            val previous = result.lastOrNull()
            // Adjacent slices with the same text are one cue, not a flicker.
            if (previous != null && previous.endMs == from && previous.text == text) {
                result[result.size - 1] = previous.copy(endMs = to)
            } else {
                result += Cue(from, to, text)
            }
        }
        return result
    }

    /** `H:MM:SS.cc` (ASS), `HH:MM:SS,mmm` (SRT) or `[HH:]MM:SS.mmm` (WebVTT). */
    private fun parseTime(raw: String): Long? {
        val parts = raw.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val secondsPart = parts.last()
        val seconds = secondsPart.substringBefore('.').toLongOrNull() ?: return null
        val fraction = secondsPart.substringAfter('.', "0").padEnd(3, '0').take(3).toLongOrNull() ?: return null
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + fraction
    }

    private fun timestamp(ms: Long): String {
        val hours = ms / 3_600_000
        val minutes = ms / 60_000 % 60
        val seconds = ms / 1000 % 60
        val millis = ms % 1000
        // Locale.ROOT: some locales format digits that no TV would parse.
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    private fun unescape(value: String) = value
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")

    private val TIMING = Regex("""(\d{1,2}:\d{2}(?::\d{2})?[.,]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}(?::\d{2})?[.,]\d{1,3})""")
    private val MARKUP_TAG = Regex("<[^>]+>")
    private val ASS_OVERRIDE = Regex("""\{[^}]*\}""")
    private val DEFAULT_ASS_FORMAT =
        listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
}
