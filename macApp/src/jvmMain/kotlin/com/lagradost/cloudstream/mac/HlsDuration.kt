package com.lagradost.cloudstream.mac

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The real length of an HLS video, read from its playlist the way ExoPlayer does. libVLC derives the length from
 * timestamps, which some hosts start far from zero (one Netflix-style stream showed 27 h for a 3 h film).
 * Returns null for live streams (no ENDLIST) or when the playlist can't be read.
 */
fun hlsDurationMs(url: String, fetch: (String) -> String): Long? {
    var text = fetch(url)
    var base = url
    if ("#EXT-X-STREAM-INF" in text) {
        // A master playlist: every variant has the same length, so the first one will do
        val variant = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        base = base.toHttpUrlOrNull()?.resolve(variant)?.toString() ?: variant
        text = fetch(base)
    }
    if ("#EXT-X-ENDLIST" !in text) return null
    val seconds = Regex("""#EXTINF:\s*([0-9.]+)""").findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
    return (seconds * 1000).toLong().takeIf { it > 0 }
}
