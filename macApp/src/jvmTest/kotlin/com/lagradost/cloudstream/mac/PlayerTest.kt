package com.lagradost.cloudstream.mac

import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
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
    fun drmComesLast() = runBlocking {
        // LivXow's live events: ClearKey DASH next to plain HLS; libVLC shows DRM as a black picture
        val drm = newDrmExtractorLink("LivXow", "SKY SPORTS FHD", "https://x/cenc.mpd", ExtractorLinkType.DASH, CLEARKEY_UUID) { quality = 1080 }
        val hls = newExtractorLink("LivXow", "FORMULA 1", "https://x/index.m3u8", ExtractorLinkType.M3U8) { quality = 720 }
        assertEquals(listOf("FORMULA 1", "SKY SPORTS FHD"), sortLinks(listOf(drm, hls), QualityPreference.AUTO).map { it.name })
        // With its key, ClearKey plays (the proxy decrypts it) and ranks by quality like the rest
        drm.key = "AAECAwQFBgcICQoLDA0ODw"
        assertEquals(listOf("SKY SPORTS FHD", "FORMULA 1"), sortLinks(listOf(drm, hls), QualityPreference.AUTO).map { it.name })
    }

    @Test
    fun dashNeedingExtraHeadersComesLast() = runBlocking {
        // MovieBox: its DASH needs X-MB-Token, which libVLC can't send, so its MP4s play first
        val tokenDash = newExtractorLink("MovieBox", "token DASH", "https://x/a.mpd", ExtractorLinkType.DASH) {
            quality = 1080; referer = "https://site/"; headers = mapOf("Origin" to "https://site", "X-MB-Token" to "t")
        }
        val plainDash = newExtractorLink("Site", "plain DASH", "https://x/b.mpd", ExtractorLinkType.DASH) {
            quality = 720; headers = mapOf("referer" to "https://site/", "User-Agent" to "ua")
        }
        val mp4 = newExtractorLink("MovieBox", "MP4", "https://x/c.mp4", ExtractorLinkType.VIDEO) {
            quality = 480; headers = mapOf("X-MB-Token" to "t") // the proxy sends these
        }
        assertEquals(listOf("plain DASH", "MP4", "token DASH"), sortLinks(listOf(tokenDash, plainDash, mp4), QualityPreference.AUTO).map { it.name })
    }

    @Test
    fun youtubeStartsWithBestAudioOthersWithFirst() {
        val yt = listOf("https://x/videoplayback?itag=139&mime=audio%2Fmp4", "https://x/videoplayback?itag=140&mime=audio%2Fmp4", "https://x/v?itag=251&mime=audio%2Fwebm")
        assertEquals(2, defaultAudio(yt))
        assertEquals("Audio 3 (webm)", audioLabel(yt[2], 2))
        assertEquals(0, defaultAudio(listOf("https://cdn/a.m4a", "https://cdn/b.m4a")))
    }

    @Test
    fun downloadsKeepHindiAndEnglishOnly() {
        val netflix = listOf("1. Czech [ces]", "3. English [eng]", "4. English - Audio Description [eng]", "9. Hindi [hin]", "17. Tamil [tam]")
        assertEquals(listOf("9. Hindi [hin]", "3. English [eng]"), keptLanguages(netflix) { it })
        assertEquals(listOf("English - [en]"), keptLanguages(listOf("Korean - [ko]", "English - [en]")) { it })
        // Neither there: everything stays, so the original language isn't lost
        assertEquals(listOf("Korean", "Japanese"), keptLanguages(listOf("Korean", "Japanese")) { it })
        assertEquals(listOf(true, true, true, false, false), listOf("English [CC]", "en", "[eng]", "Bengali", "French").map(::isEnglish))
        assertEquals("English", bestEnglish(listOf("English (1)", "English [CC]", "English", "English SDH")) { it })
        assertEquals("English [CC]", bestEnglish(listOf("English [CC]")) { it })
        assertEquals("1\n00:00:01,500 --> 01:02:03,004\nHi\nthere\n", toSrt(listOf(Cue(1500, 3_723_004, "Hi\nthere"))))
    }

    @Test
    fun hindiAudioIsPreferred() {
        // libVLC's names for HLS audio groups and MKV tracks
        assertEquals(1, hindiTrack(listOf("English - [eng]", "Hindi - [hin]", "Tamil - [tam]")))
        assertEquals(2, hindiTrack(listOf("Track 1 - [en]", "Track 2 - [ta]", "Track 3 - [hi]")))
        assertEquals(0, hindiTrack(listOf("hi-IN", "en-US")))
        assertEquals(null, hindiTrack(listOf("English - [eng]", "Chinese - [chi]", "Hindustani notes")))
        // YouTube dubs: the Hindi ones win, and among them the best format
        val dubbed = listOf(
            "https://x/videoplayback?itag=251&xtags=acont%3Doriginal%3Alang%3Den&mime=audio%2Fwebm",
            "https://x/videoplayback?itag=140&xtags=acont%3Ddubbed%3Alang%3Dhi&mime=audio%2Fmp4",
            "https://x/videoplayback?itag=251&xtags=acont%3Ddubbed%3Alang%3Dhi&mime=audio%2Fwebm",
        )
        assertEquals(2, defaultAudio(dubbed))
        assertEquals(0, defaultAudio(dubbed.take(1)))
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
