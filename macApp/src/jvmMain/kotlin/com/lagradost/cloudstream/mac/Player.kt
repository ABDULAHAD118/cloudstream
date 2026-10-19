package com.lagradost.cloudstream.mac

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.io.File
import java.util.TreeMap

/** Hands a stream to an installed external player. IINA is preferred because it can send every HTTP header. */
object Player {
    private val iina = File("/Applications/IINA.app/Contents/MacOS/iina-cli")
    private val vlc = File("/Applications/VLC.app/Contents/MacOS/VLC")

    val available: String?
        get() = when {
            iina.canExecute() -> "IINA"
            vlc.canExecute() -> "VLC"
            else -> null
        }

    fun headersFor(link: ExtractorLink): Map<String, String> {
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        headers["User-Agent"] = USER_AGENT
        if (link.referer.isNotBlank()) headers["Referer"] = link.referer
        // Extension headers win, matching ExoPlayer's behaviour in the Android app
        headers.putAll(link.headers)
        return headers
    }

    /** Builds the player command; split out so it can be tested without launching anything. */
    fun command(player: String, link: ExtractorLink, title: String, subtitleFiles: List<File>): List<String> {
        val headers = headersFor(link)
        return if (player == "IINA") {
            listOf(iina.path, link.url, "--mpv-force-media-title=$title") +
                    // -append adds one entry, so header values containing commas aren't split
                    headers.map { (k, v) -> "--mpv-http-header-fields-append=$k: $v" } +
                    subtitleFiles.map { "--mpv-sub-files-append=${it.path}" }
        } else {
            // VLC can only send these two headers; streams needing cookies or tokens in other headers need IINA
            listOfNotNull(
                vlc.path, link.url, "--meta-title=$title",
                headers["Referer"]?.let { "--http-referrer=$it" },
                headers["User-Agent"]?.let { "--http-user-agent=$it" },
                subtitleFiles.firstOrNull()?.let { "--sub-file=${it.path}" },
            )
        }
    }

    /** Returns an error message, or null when the player was started. */
    suspend fun play(link: ExtractorLink, title: String, subtitles: List<SubtitleFile>): String? {
        val player = available ?: return "Install IINA (iina.io) or VLC (videolan.org) to play videos."
        // Players can't send headers for subtitle requests, so fetch them here first
        val subDir = File(Extensions.dataDir, "subtitles").apply { deleteRecursively(); mkdirs() }
        val files = subtitles.mapIndexedNotNull { i, sub ->
            runCatching {
                val ext = sub.url.substringBefore('?').substringAfterLast('.', "srt").take(4)
                File(subDir, "$i-${sub.lang.replace(Regex("[^\\w-]"), "_")}.$ext").apply {
                    writeText(app.get(sub.url, headers = sub.headers ?: emptyMap()).text)
                }
            }.getOrNull()
        }
        return runCatching { ProcessBuilder(command(player, link, title, files)).inheritIO().start(); null }
            .getOrElse { "Couldn't start $player: ${it.message}" }
    }
}
