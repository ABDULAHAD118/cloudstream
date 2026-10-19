package com.lagradost.cloudstream.mac

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HlsDurationTest {
    private val media = "#EXTM3U\n#EXT-X-TARGETDURATION:19\n#EXTINF:16.5,\na.jpg\n#EXTINF:7.5,\nb.jpg\n#EXT-X-ENDLIST\n"

    @Test
    fun masterPlaylistFollowsFirstVariant() {
        val pages = mapOf(
            "https://h/master.m3u8" to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n1080p/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2\n720p/index.m3u8\n",
            "https://h/1080p/index.m3u8" to media,
        )
        assertEquals(24_000, hlsDurationMs("https://h/master.m3u8") { pages.getValue(it) })
    }

    @Test
    fun liveStreamsHaveNoLength() = assertNull(hlsDurationMs("https://h/live.m3u8") { media.replace("#EXT-X-ENDLIST", "") })
}
