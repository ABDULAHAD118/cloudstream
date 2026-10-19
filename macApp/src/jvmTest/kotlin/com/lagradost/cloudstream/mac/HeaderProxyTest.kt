package com.lagradost.cloudstream.mac

import kotlin.test.Test
import kotlin.test.assertEquals

class HeaderProxyTest {
    @Test
    fun rewritesSegmentsAndKeyUris() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:4.0,
            seg1.ts
            https://cdn.other/seg2.ts
        """.trimIndent()
        val out = HeaderProxy.rewritePlaylist(playlist, "https://host/a/b/index.m3u8") { "P($it)" }
        assertEquals(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="P(https://host/a/b/key.bin)"
            #EXTINF:4.0,
            P(https://host/a/b/seg1.ts)
            P(https://cdn.other/seg2.ts)
            """.trimIndent(),
            out,
        )
    }
}
