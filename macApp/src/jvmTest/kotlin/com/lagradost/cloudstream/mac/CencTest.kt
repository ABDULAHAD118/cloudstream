package com.lagradost.cloudstream.mac

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ClearKey "cenc" as the live channels send it: protected init segments and subsample-encrypted fragments. */
class CencTest {
    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val content = body.fold(ByteArray(0)) { a, b -> a + b }
        return ByteBuffer.allocate(8 + content.size).putInt(8 + content.size).put(type.toByteArray()).put(content).array()
    }
    private fun ints(vararg v: Int) = ByteBuffer.allocate(4 * v.size).apply { v.forEach { putInt(it) } }.array()

    private val key = Random(1).nextBytes(16)

    @Test
    fun decryptsSubsampleFragments() {
        val samples = listOf(Random(2).nextBytes(300), Random(3).nextBytes(77))
        val ivs = listOf(Random(4).nextBytes(8), Random(5).nextBytes(8))
        val clear = 5 // NAL header bytes stay readable
        val encrypted = samples.zip(ivs).map { (s, iv) ->
            val c = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv.copyOf(16))) }
            s.copyOfRange(0, clear) + c.doFinal(s, clear, s.size - clear)
        }
        val senc = ByteArrayOutputStream().apply {
            write(ints(0x2, samples.size))
            samples.zip(ivs).forEach { (s, iv) -> write(iv); write(ByteBuffer.allocate(8).putShort(1).putShort(clear.toShort()).putInt(s.size - clear).array()) }
        }.toByteArray()
        fun moof(dataOffset: Int) = box("moof", box("mfhd", ints(0, 1)), box("traf",
            box("tfhd", ints(0x020000, 1)),
            box("trun", ints(0x201, samples.size, dataOffset, samples[0].size, samples[1].size)),
            box("senc", senc),
        ))
        val headerSize = moof(0).size + 8
        val segment = moof(headerSize) + box("mdat", *encrypted.toTypedArray())

        val out = Cenc.process(segment, key)
        assertContentEquals(samples.reduce { a, b -> a + b }, out.copyOfRange(headerSize, out.size))
        assertContentEquals(moof(headerSize), out.copyOfRange(0, headerSize - 8)) // boxes untouched
    }

    @Test
    fun initLosesItsProtection() {
        val avcC = box("avcC", byteArrayOf(1, 2, 3))
        val sinf = box("sinf", box("frma", "avc1".toByteArray()), box("schm", ints(0, 0x63656e63, 0x10000)))
        val encv = box("encv", ByteArray(78) { 7 }, avcC, sinf)
        val init = box("ftyp", "isom".toByteArray()) + box("moov",
            box("pssh", ints(0, 0)),
            box("trak", box("mdia", box("minf", box("stbl", box("stsd", ints(0, 1), encv))))),
        )
        val out = Cenc.process(init, key)
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue("avc1" in text && "avcC" in text)
        assertFalse("encv" in text || "sinf" in text || "pssh" in text, text)
        val expected = box("ftyp", "isom".toByteArray()) + box("moov",
            box("trak", box("mdia", box("minf", box("stbl", box("stsd", ints(0, 1), box("avc1", ByteArray(78) { 7 }, avcC)))))),
        )
        assertContentEquals(expected, out) // every size rewritten
    }

    @Test
    fun keysInBothSpellings() {
        assertContentEquals(key, Cenc.decodeKey(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key)))
        assertContentEquals(key, Cenc.decodeKey(key.joinToString("") { "%02x".format(it) }))
        assertEquals(null, Cenc.decodeKey("short"))
    }

    @Test
    fun absoluteBaseUrlsGoThroughTheProxy() {
        val relative = """<MPD><Period><SegmentTemplate media="v_${'$'}Number${'$'}.mp4"/></Period></MPD>"""
        assertEquals(relative, HeaderProxy.rewriteManifest(relative) { "proxy($it)" }) // served from the proxy, so already relative to it
        val absolute = """<MPD><BaseURL>https://other/dir/</BaseURL></MPD>"""
        assertEquals("""<MPD><BaseURL>proxy(https://other/dir/)</BaseURL></MPD>""", HeaderProxy.rewriteManifest(absolute) { "proxy($it)" })
    }

    private fun period(id: Int, start: Int, startNumber: Int, segments: String, duration: String = "") =
        """<Period id="$id" start="PT${start}S"$duration><AdaptationSet mimeType="video/mp4"><Representation id="1">""" +
            """<SegmentTemplate timescale="25000" media="v_${'$'}Number${'$'}.mp4" initialization="v_init.mp4" startNumber="$startNumber">""" +
            """<SegmentTimeline>$segments</SegmentTimeline></SegmentTemplate></Representation></AdaptationSet></Period>"""

    @Test
    fun adBreakPeriodsJoinIntoOne() {
        // As Willow sends it: the match, a 33 s ad break, the match again; numbers and times run on
        val mpd = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic">""" +
            period(1, 100, 10, """<S t="0" d="50000" r="3"/>""", """ duration="PT8S"""") +
            period(2, 108, 14, """<S t="200000" d="50000" r="1"/>""", """ duration="PT4S"""") +
            period(3, 112, 16, """<S t="300000" d="50000" r="5"/>""") + "</MPD>"
        val out = HeaderProxy.mergePeriods(mpd)
        assertEquals(1, Regex("<Period[ >]").findAll(out).count(), out)
        assertEquals(listOf("0", "200000", "300000"), Regex("""<S [^>]*t="(\d+)"""").findAll(out).map { it.groupValues[1] }.toList())
        assertFalse("duration=" in out, out)
        assertTrue("""startNumber="10"""" in out)
    }

    @Test
    fun periodsThatDontLineUpStay() {
        val gap = """<MPD type="dynamic">""" + period(1, 0, 10, """<S t="0" d="50000" r="3"/>""") +
            period(2, 8, 20, """<S t="200000" d="50000"/>""") + "</MPD>" // numbers 14..19 missing: an inserted ad
        assertEquals(gap, HeaderProxy.mergePeriods(gap))
        val vod = gap.replace("dynamic", "static")
        assertEquals(vod, HeaderProxy.mergePeriods(vod))
    }
}
