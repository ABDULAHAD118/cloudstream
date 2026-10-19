package com.lagradost.cloudstream.mac

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitlesTest {
    @Test
    fun srt() {
        val cues = SubtitleParser.parse("﻿1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>Hello</i>\r\nthere\r\n\r\n2\r\n01:02:03,004 --> 01:02:04,000\r\nBye\r\n")
        assertEquals(listOf(Cue(1500, 3000, "Hello\nthere"), Cue(3723004, 3724000, "Bye")), cues)
    }

    @Test
    fun vttWithShortTimesSettingsAndEntities() {
        val cues = SubtitleParser.parse("WEBVTT\n\nNOTE x\n\n00:05.000 --> 00:06.250 align:start line:90%\n<c.yellow>Tom &amp; Jerry</c>\n")
        assertEquals(listOf(Cue(5000, 6250, "Tom & Jerry")), cues)
    }

    @Test
    fun assDialogueKeepsCommasAndLineBreaks() {
        val ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:02.10,0:00:04.00,Default,,0,0,0,,{\\an8}Wait, what?\\NNo way\n"
        assertEquals(listOf(Cue(2100, 4000, "Wait, what?\nNo way")), SubtitleParser.parse(ass))
    }

    @Test
    fun cueAtTime() {
        val cues = listOf(Cue(1000, 2000, "a"), Cue(1500, 2500, "b"))
        assertEquals("a\nb", SubtitleParser.at(cues, 1600))
        assertNull(SubtitleParser.at(cues, 2600))
    }

    @Test
    fun hlsSubtitleTracksAndSegments() {
        val master = "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"English-IN [CC]\",LANGUAGE=\"en-IN\",URI=\"subs/en.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1,SUBTITLES=\"subs\"\n720p.m3u8\n"
        val tracks = HlsSubtitles.tracks("https://h/x/master.m3u8", master)
        assertEquals(listOf(HlsSubtitleTrack("English-IN [CC]", "en-IN", "https://h/x/subs/en.m3u8")), tracks)
        val pages = mapOf(
            "https://h/x/subs/en.m3u8" to "#EXTM3U\n#EXTINF:10,\na.vtt\n#EXTINF:10,\nb.vtt\n#EXT-X-ENDLIST",
            "https://h/x/subs/a.vtt" to "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nOne\n",
            "https://h/x/subs/b.vtt" to "WEBVTT\n\n00:00:11.000 --> 00:00:12.000\nTwo\n",
        )
        assertEquals(listOf("One", "Two"), HlsSubtitles.load(tracks.single()) { pages.getValue(it) }.map { it.text })
    }
}
