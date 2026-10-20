package com.lagradost.cloudstream.mac

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals

class QuietLogTest {
    @Test
    fun dropsStoppedRequestsOnly() {
        val captured = ByteArrayOutputStream()
        val err = QuietLog.filtered(PrintStream(captured, true))
        java.io.IOException("Canceled").printStackTrace(err)
        java.net.SocketException("Socket closed").printStackTrace(err)
        err.println("Downloading “Berlin – E1 · The Collection” हिन्दी") // printed text in any language comes out as it went in
        IllegalStateException("boom").printStackTrace(err)
        err.flush()
        val lines = captured.toString(Charsets.UTF_8).lines()
        assertEquals("Downloading “Berlin – E1 · The Collection” हिन्दी", lines.first())
        assertEquals("java.lang.IllegalStateException: boom", lines[1])
        assertEquals(true, lines[2].trimStart().startsWith("at "))
        assertEquals(0, lines.count { "Canceled" in it || "Socket closed" in it })
    }
}
