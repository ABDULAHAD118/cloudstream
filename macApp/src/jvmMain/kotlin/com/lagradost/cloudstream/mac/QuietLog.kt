package com.lagradost.cloudstream.mac

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * NiceHttp prints a line and a full stack trace for every request that's stopped on purpose (leaving a page while it
 * loads, a provider timing out in search, closing the download picker), which buries real errors. This drops just
 * those from stdout/stderr; CS_HTTP_LOG=1 keeps them.
 */
object QuietLog {
    private val noise = listOf("java.io.IOException: Canceled", "java.net.SocketException: Socket closed")
    private fun isTraceLine(line: String) = line.trimStart().let {
        it.startsWith("at ") || it.startsWith("... ") || it.startsWith("Caused by: ") || it.startsWith("Suppressed: ")
    }

    /** Line-by-line filter on bytes, so text in any language passes through as it was; per thread, since traces interleave. */
    private class Filter(private val target: PrintStream) : OutputStream() {
        private val line = ThreadLocal.withInitial { ByteArrayOutputStream() }
        private val skipping = ThreadLocal.withInitial { false }

        override fun write(b: Int) {
            val current = line.get()
            current.write(b)
            if (b == '\n'.code) { emit(current.toByteArray()); current.reset() }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) write(b[i].toInt() and 0xFF)
        }

        private fun emit(bytes: ByteArray) {
            val text = String(bytes, Charsets.UTF_8).trimEnd('\n', '\r')
            val drop = when {
                text.startsWith("Exception in NiceHttp: java.io.IOException Canceled") -> true
                noise.any { text.startsWith(it) } -> { skipping.set(true); true }
                skipping.get() && isTraceLine(text) -> true
                else -> { skipping.set(false); false }
            }
            if (!drop) synchronized(target) { target.write(bytes); target.flush() }
        }
    }

    fun filtered(target: PrintStream) = PrintStream(Filter(target), true, Charsets.UTF_8)

    fun install() {
        if (System.getenv("CS_HTTP_LOG") == "1") return
        System.setOut(filtered(System.out))
        System.setErr(filtered(System.err))
    }
}
