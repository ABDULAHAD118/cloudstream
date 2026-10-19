package android.util

/** android.util.Base64 on top of java.util.Base64, with Android's lenient decoding (whitespace, missing padding). */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    @JvmStatic fun decode(str: String?, flags: Int): ByteArray = decode(str.orEmpty().toByteArray(Charsets.US_ASCII), flags)

    @JvmStatic fun decode(input: ByteArray, flags: Int): ByteArray = decode(input, 0, input.size, flags)

    @JvmStatic fun decode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray {
        // Android accepts both alphabets either way and ignores line breaks
        val clean = String(input, offset, len, Charsets.US_ASCII).filterNot { it.isWhitespace() }
            .replace('-', '+').replace('_', '/').trimEnd('=')
        try {
            return java.util.Base64.getDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("bad base-64", e) // same exception type Android throws
        }
    }

    @JvmStatic fun encode(input: ByteArray, flags: Int): ByteArray = encodeToString(input, flags).toByteArray(Charsets.US_ASCII)

    @JvmStatic fun encode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray = encode(input.copyOfRange(offset, offset + len), flags)

    @JvmStatic fun encodeToString(input: ByteArray, flags: Int): String = encodeToString(input, 0, input.size, flags)

    @JvmStatic fun encodeToString(input: ByteArray, offset: Int, len: Int, flags: Int): String {
        var encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        val text = encoder.encodeToString(input.copyOfRange(offset, offset + len))
        if (flags and NO_WRAP != 0) return text
        // Android's default wraps at 76 characters and ends with a newline
        val newline = if (flags and CRLF != 0) "\r\n" else "\n"
        return text.chunked(76).joinToString(newline, postfix = newline)
    }
}
