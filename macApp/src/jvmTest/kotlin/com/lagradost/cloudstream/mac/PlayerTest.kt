package com.lagradost.cloudstream.mac

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
    fun repoUrlNormalisation() {
        assertEquals("https://a.b/repo.json", Extensions.normalizeRepoUrl(" cloudstreamrepo://a.b/repo.json "))
        assertEquals("https://a.b/repo.json", Extensions.normalizeRepoUrl("https://cs.repo/?https://a.b/repo.json"))
        assertEquals("http://a.b/r.json", Extensions.normalizeRepoUrl("http://a.b/r.json"))
    }
}
