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

    @Test
    fun masterPlaylistKeepsAudioButNotSubtitles() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Arabic",LANGUAGE="ar",URI="https://subs/ar.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="hin",NAME="9. Hindi",URI="a/9.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aac",SUBTITLES="subs"
            v/400.m3u8
        """.trimIndent()
        assertEquals(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="hin",NAME="9. Hindi",URI="P(https://host/a/9.m3u8)"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aac"
            P(https://host/v/400.m3u8)
            """.trimIndent(),
            HeaderProxy.rewritePlaylist(master, "https://host/index.m3u8") { "P($it)" },
        )
    }
}
