package com.lagradost.cloudstream.mac

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One subtitle line on screen from [startMs] to [endMs]. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/**
 * The app draws subtitles itself rather than through libVLC: libVLC found a stream's HLS subtitle tracks but
 * didn't show them, and drawing them ourselves allows readable, Netflix-style text. Handles SRT, WebVTT and
 * ASS/SSA, the formats extensions deliver.
 */
object SubtitleParser {
    private val time = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")
    private val arrow = Regex("""^\s*(\S+)\s*-->\s*(\S+)""")
    private val tags = Regex("""<[^>]+>|\{\\[^}]*\}""")

    fun parse(text: String): List<Cue> {
        val body = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val cues = if (body.contains("[Events]", ignoreCase = true) || body.lineSequence().any { it.startsWith("Dialogue:") }) parseAss(body)
        else parseSrtOrVtt(body)
        return cues.filter { it.text.isNotBlank() && it.endMs > it.startMs }.sortedBy { it.startMs }
    }

    fun timeMs(value: String): Long? {
        val m = time.matchEntire(value.trim()) ?: return null
        val (h, min, s, frac) = m.destructured
        val ms = frac.padEnd(3, '0').toLong()
        return ((h.toLongOrNull() ?: 0) * 3600 + min.toLong() * 60 + s.toLong()) * 1000 + ms
    }

    private fun clean(line: String) = line.replace(tags, "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").trim()

    /** SRT and WebVTT share the same shape: an optional id line, "start --> end", then text up to a blank line. */
    private fun parseSrtOrVtt(body: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val lines = body.split('\n')
        var i = 0
        while (i < lines.size) {
            val m = arrow.find(lines[i])
            if (m == null) { i++; continue }
            val start = timeMs(m.groupValues[1]); val end = timeMs(m.groupValues[2])
            i++
            val text = buildList { while (i < lines.size && lines[i].isNotBlank()) add(clean(lines[i++])) }.filter { it.isNotEmpty() }
            if (start != null && end != null) cues += Cue(start, end, text.joinToString("\n"))
        }
        return cues
    }

    /** ASS/SSA "Dialogue:" lines; the text is the last field and may itself contain commas. */
    private fun parseAss(body: String): List<Cue> {
        var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        return body.lineSequence().mapNotNull { line ->
            if (line.startsWith("Format:", ignoreCase = true) && fields.isNotEmpty()) {
                val f = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                if ("text" in f) fields = f
                return@mapNotNull null
            }
            if (!line.startsWith("Dialogue:")) return@mapNotNull null
            val parts = line.substringAfter(':').split(',', limit = fields.size)
            if (parts.size < fields.size) return@mapNotNull null
            val start = timeMs(parts[fields.indexOf("start")]) ?: return@mapNotNull null
            val end = timeMs(parts[fields.indexOf("end")]) ?: return@mapNotNull null
            Cue(start, end, clean(parts[fields.indexOf("text")].replace("\\N", "\n").replace("\\n", "\n")))
        }.toList()
    }

    /** The lines to show at [timeMs], usually one cue (overlapping cues stack). */
    fun at(cues: List<Cue>, timeMs: Long): String? =
        cues.filter { timeMs in it.startMs until it.endMs }.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }
}

/** A subtitle track declared inside an HLS master playlist (#EXT-X-MEDIA:TYPE=SUBTITLES). */
data class HlsSubtitleTrack(val name: String, val language: String?, val playlistUrl: String)

object HlsSubtitles {
    private val attr = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun tracks(masterUrl: String, master: String): List<HlsSubtitleTrack> = master.lines()
        .filter { it.startsWith("#EXT-X-MEDIA:") && "TYPE=SUBTITLES" in it }
        .mapNotNull { line ->
            val a = attr.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
            val uri = a["URI"] ?: return@mapNotNull null
            HlsSubtitleTrack(a["NAME"] ?: a["LANGUAGE"] ?: "Subtitles", a["LANGUAGE"], masterUrl.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: uri)
        }
        .distinctBy { it.playlistUrl }

    /** A subtitle playlist lists WebVTT segments; fetch them all and join their cues. */
    fun load(track: HlsSubtitleTrack, fetch: (String) -> String): List<Cue> {
        val playlist = fetch(track.playlistUrl)
        if (!playlist.trimStart().startsWith("#EXTM3U")) return SubtitleParser.parse(playlist) // a plain file, not a playlist
        val base = track.playlistUrl.toHttpUrlOrNull()
        val parts = playlist.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { base?.resolve(it)?.toString() ?: it }
        // shortcut: X-TIMESTAMP-MAP offsets are ignored; they match the video's own start offset on the streams seen so far
        return parts.flatMap { runCatching { SubtitleParser.parse(fetch(it)) }.getOrDefault(emptyList()) }
            .distinctBy { Triple(it.startMs, it.endMs, it.text) }.sortedBy { it.startMs }
    }
}
