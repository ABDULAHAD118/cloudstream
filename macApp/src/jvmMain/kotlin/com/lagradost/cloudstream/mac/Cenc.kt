package com.lagradost.cloudstream.mac

import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ClearKey for fragmented MP4 ("cenc", AES-CTR), which ExoPlayer plays on Android and libVLC 3 can't: the proxy
 * hands libVLC init segments with the encryption boxes taken out and media segments with their samples decrypted.
 * Used for live channels (CNC Verse's LivXow, Cricify) whose extensions give the key with the link.
 */
object Cenc {
    /** The 16-byte content key of a ClearKey link, or null for other DRM (Widevine needs a licence server). */
    fun keyOf(link: DrmExtractorLink): ByteArray? {
        if (link.uuid.toString() != CLEARKEY_UUID.toString() && link.kty != "oct") return null
        return link.key?.let(::decodeKey)
    }

    /** ClearKey JSON writes keys as unpadded base64url; some extensions pass 32 hex digits instead. */
    fun decodeKey(text: String): ByteArray? = runCatching {
        if (text.length == 32 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        else Base64.getUrlDecoder().decode(text.trimEnd('='))
    }.getOrNull()?.takeIf { it.size == 16 }

    /** An init segment (moov) loses its protection boxes; a media segment (moof + mdat) is decrypted. */
    fun process(bytes: ByteArray, key: ByteArray): ByteArray {
        val top = boxes(bytes, 0, bytes.size)
        return when {
            top.any { it.type == "moov" } -> clearInit(bytes, top)
            top.any { it.type == "moof" } -> decryptSegment(bytes, top, key)
            else -> bytes
        }
    }

    private class Box(val type: String, val start: Int, val headerSize: Int, val end: Int) {
        val body get() = start + headerSize
    }

    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        val buf = ByteBuffer.wrap(b)
        var at = from
        while (at + 8 <= to) {
            var size = buf.getInt(at).toLong() and 0xffffffffL
            val type = String(b, at + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = buf.getLong(at + 8); header = 16 }
            if (size == 0L) size = (to - at).toLong()
            if (size < header || at + size > to) break // truncated: leave the rest untouched
            out += Box(type, at, header, (at + size).toInt())
            at += size.toInt()
        }
        return out
    }

    // Boxes whose body is only more boxes, and the fixed fields before the child boxes of the others we enter
    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "mvex", "edts", "dinf")
    private val skipBeforeChildren = mapOf("stsd" to 8, "encv" to 78, "enca" to 28)

    /** encv/enca become their original format (frma) without the sinf box; pssh boxes go. Sizes are rewritten. */
    private fun clearInit(b: ByteArray, top: List<Box>): ByteArray {
        fun write(box: Box, out: ByteArrayOutputStream) {
            if (box.type == "pssh" || box.type == "sinf") return
            val skip = skipBeforeChildren[box.type]
            if (box.type !in containers && skip == null) { out.write(b, box.start, box.end - box.start); return }
            val children = boxes(b, box.body + (skip ?: 0), box.end)
            val type = if (box.type == "encv" || box.type == "enca") originalFormat(b, children) ?: box.type else box.type
            val body = ByteArrayOutputStream()
            if (skip != null) body.write(b, box.body, skip)
            children.forEach { write(it, body) }
            out.write(ByteBuffer.allocate(8).putInt(8 + body.size()).put(type.toByteArray(Charsets.ISO_8859_1)).array())
            body.writeTo(out)
        }
        return ByteArrayOutputStream(b.size).also { out -> top.forEach { write(it, out) } }.toByteArray()
    }

    private fun originalFormat(b: ByteArray, children: List<Box>): String? {
        val sinf = children.firstOrNull { it.type == "sinf" } ?: return null
        val frma = boxes(b, sinf.body, sinf.end).firstOrNull { it.type == "frma" } ?: return null
        return String(b, frma.body, 4, Charsets.ISO_8859_1)
    }

    private fun decryptSegment(source: ByteArray, top: List<Box>, key: ByteArray): ByteArray {
        val b = source.copyOf()
        val buf = ByteBuffer.wrap(b)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val secret = SecretKeySpec(key, "AES")
        for (moof in top.filter { it.type == "moof" }) for (traf in boxes(b, moof.body, moof.end).filter { it.type == "traf" }) {
            val parts = boxes(b, traf.body, traf.end).associateBy { it.type }
            val tfhd = parts["tfhd"] ?: continue
            val trun = parts["trun"] ?: continue
            val senc = parts["senc"] ?: continue // a clear fragment

            val tfhdFlags = buf.getInt(tfhd.body) and 0xffffff
            var at = tfhd.body + 8 // version/flags, track_ID
            val base = if (tfhdFlags and 0x1 != 0) buf.getLong(at).toInt().also { at += 8 } else moof.start
            if (tfhdFlags and 0x2 != 0) at += 4
            if (tfhdFlags and 0x8 != 0) at += 4
            val defaultSize = if (tfhdFlags and 0x10 != 0) buf.getInt(at) else 0

            val trunFlags = buf.getInt(trun.body) and 0xffffff
            val count = buf.getInt(trun.body + 4)
            at = trun.body + 8
            var offset = base + if (trunFlags and 0x1 != 0) buf.getInt(at).also { at += 4 } else 0
            if (trunFlags and 0x4 != 0) at += 4
            val sizes = IntArray(count) {
                if (trunFlags and 0x100 != 0) at += 4
                val size = if (trunFlags and 0x200 != 0) buf.getInt(at).also { at += 4 } else defaultSize
                if (trunFlags and 0x400 != 0) at += 4
                if (trunFlags and 0x800 != 0) at += 4
                size
            }

            val entries = sencEntries(buf, senc, count) ?: continue
            for (i in 0 until count) {
                val (iv, subsamples) = entries[i]
                cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv.copyOf(16))) // 8-byte IVs are zero-padded
                if (subsamples.isEmpty()) cipher.update(b, offset, sizes[i], b, offset)
                else {
                    // The counter runs on across a sample's protected ranges; clear ranges (NAL headers) are skipped
                    var pos = offset
                    for ((clear, protected) in subsamples) {
                        pos += clear
                        if (protected > 0) cipher.update(b, pos, protected, b, pos)
                        pos += protected
                    }
                }
                offset += sizes[i]
            }
        }
        return b
    }

    /** Each sample's IV and (clear, protected) byte ranges. The IV size lives in the init's tenc, so try 8 and 16. */
    private fun sencEntries(buf: ByteBuffer, senc: Box, samples: Int): List<Pair<ByteArray, List<Pair<Int, Int>>>>? {
        val flags = buf.getInt(senc.body) and 0xffffff
        val count = buf.getInt(senc.body + 4)
        if (count != samples) return null
        for (ivSize in intArrayOf(8, 16)) {
            var at = senc.body + 8
            val parsed = runCatching {
                List(count) {
                    val iv = ByteArray(ivSize).also { buf.get(at, it); at += ivSize }
                    val subsamples = if (flags and 0x2 != 0) {
                        val n = buf.getShort(at).toInt() and 0xffff; at += 2
                        List(n) { (buf.getShort(at).toInt() and 0xffff to buf.getInt(at + 2)).also { at += 6 } }
                    } else emptyList()
                    iv to subsamples
                }
            }.getOrNull()
            if (parsed != null && at == senc.end) return parsed
        }
        return null
    }
}
