package com.lagradost.cloudstream.mac

import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerTest {
    private val link = runBlocking {
        newExtractorLink("src", "name", "https://x/v.m3u8", ExtractorLinkType.M3U8) {
            referer = "https://site/"
            headers = mapOf("referer" to "https://override/", "Cookie" to "a=1, b=2")
        }
    }

    @Test
    fun extensionHeadersOverrideDefaultsCaseInsensitively() {
        val h = Player.headersFor(link)
        assertEquals("https://override/", h["Referer"])
        assertEquals(3, h.size) // User-Agent, Referer, Cookie
    }

    @Test
    fun iinaGetsEveryHeaderAsSeparateAppend() {
        val cmd = Player.command("IINA", link, "T", listOf(File("/tmp/s.srt")))
        assertEquals("https://x/v.m3u8", cmd[1])
        assertTrue("--mpv-http-header-fields-append=Cookie: a=1, b=2" in cmd)
        assertTrue("--mpv-sub-files-append=/tmp/s.srt" in cmd)
    }

    @Test
    fun vlcGetsOnlyRefererAndUserAgent() {
        val cmd = Player.command("VLC", link, "T", emptyList())
        assertTrue("--http-referrer=https://override/" in cmd)
        assertTrue(cmd.none { it.contains("Cookie") })
    }

    @Test
    fun linksBestFirstUnderTheQualityCap() = runBlocking {
        val links = listOf(720, 2160, 1080, 1440, 0, 1080).mapIndexed { i, q ->
            newExtractorLink("s", "l$i", "https://x/$i", ExtractorLinkType.VIDEO) { quality = q }
        }
        assertEquals(listOf(2160, 1440, 1080, 1080, 720, 0), sortLinks(links, 2160).map { it.quality }) // 4K = best
        assertEquals(listOf(1080, 1080, 720, 0, 1440, 2160), sortLinks(links, 1080).map { it.quality })
    }

    @Test
    fun seekableSourcesComeFirst() = runBlocking {
        val audio = listOf(newAudioFile("https://x/videoplayback?itag=140&mime=audio%2Fmp4"))
        val vp9 = newExtractorLink("YouTube", "VP9", "https://x/videoplayback?itag=313&mime=video%2Fwebm", ExtractorLinkType.VIDEO) { quality = 2160; audioTracks = audio }
        val h264 = newExtractorLink("YouTube", "H264", "https://x/videoplayback?itag=137&mime=video%2Fmp4", ExtractorLinkType.VIDEO) { quality = 1080; audioTracks = audio }
        val plain = newExtractorLink("Site", "file", "https://x/movie.webm", ExtractorLinkType.VIDEO) { quality = 720 }
        assertEquals(listOf("H264", "file", "VP9"), sortLinks(listOf(vp9, plain, h264), 2160).map { it.name })
    }

    @Test
    fun youtubeStartsWithBestAudioOthersWithFirst() {
        val yt = listOf("https://x/videoplayback?itag=139&mime=audio%2Fmp4", "https://x/videoplayback?itag=140&mime=audio%2Fmp4", "https://x/v?itag=251&mime=audio%2Fwebm")
        assertEquals(2, defaultAudio(yt))
        assertEquals("Audio 3 (webm)", audioLabel(yt[2], 2))
        assertEquals(0, defaultAudio(listOf("https://cdn/a.m4a", "https://cdn/b.m4a")))
    }

    @Test
    fun downloadsAreSavedInAFittingFileType() {
        fun ext(url: String, type: ExtractorLinkType, audio: String? = null) = Downloads.extensionFor(DownloadSource(url, type, emptyMap(), audio))
        assertEquals("ts", ext("https://h/master.m3u8", ExtractorLinkType.M3U8))
        assertEquals("mkv", ext("https://h/video", ExtractorLinkType.VIDEO, audio = "https://h/audio"))
        assertEquals("mkv", ext("https://h/manifest.mpd", ExtractorLinkType.DASH))
        assertEquals("webm", ext("https://h/clip.WEBM?token=1", ExtractorLinkType.VIDEO))
        assertEquals("mp4", ext("https://h/videoplayback?id=1", ExtractorLinkType.VIDEO))
    }

    @Test
    fun plotHtmlBecomesText() = assertEquals("“Big Buck Bunny” & co\nline 2 'x'", htmlToText("&#8220;Big Buck Bunny&#x201D; &amp; co<br/>line 2 <b>&apos;x&apos;</b>"))

    @Test
    fun repoUrlNormalisation() {
        assertEquals("https://a.b/repo.json", Extensions.normalizeRepoUrl(" cloudstreamrepo://a.b/repo.json "))
        assertEquals("https://a.b/repo.json", Extensions.normalizeRepoUrl("https://cs.repo/?https://a.b/repo.json"))
        assertEquals("http://a.b/r.json", Extensions.normalizeRepoUrl("http://a.b/r.json"))
    }
}
