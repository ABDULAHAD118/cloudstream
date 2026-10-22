package com.lagradost.cloudstream.mac

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

/**
 * Whether this computer can reach the internet, for the "You're offline" notices.
 *
 * Downloads, the Library and the player work without it; Home, Search, opening a title, extension repositories
 * and live channels need it. A TCP connection is used rather than a ping because macOS doesn't let apps send ICMP
 * without extra rights, and a captive portal can answer DNS while dropping everything else.
 */
object Network {
    /** Pinned by tests, and by CS_OFFLINE=1 for looking at the offline screens; null means "ask the network". */
    var pinned: Boolean? = if (System.getenv("CS_OFFLINE") == "1") false else null
        private set

    var online by mutableStateOf(pinned ?: true)
        private set

    /** Tried in order; the first that accepts a connection means we're online. */
    private val probes = listOf("1.1.1.1" to 443, "8.8.8.8" to 53, "9.9.9.9" to 443)

    /** Completed by the first probe (or by [pin]), so work that needs the internet can wait for a real answer. */
    private val firstCheck = CompletableDeferred<Unit>()

    /** Keeps [online] current: every 15 s online, every 3 s offline so it recovers soon after the Wi-Fi is back. */
    fun start() {
        AppScope.launch {
            while (true) {
                checkNow()
                firstCheck.complete(Unit)
                delay(if (online) 15_000 else 3_000)
            }
        }
    }

    /** Suspends until whether there's a connection has been decided once. */
    suspend fun awaitFirstCheck() = firstCheck.await()

    /** Probes right away, blocking for a moment at most; used by "Try again" and the tests. */
    fun checkNow() {
        online = pinned ?: probe()
    }

    /** Probes without blocking the caller, for buttons. */
    fun refresh() = AppScope.launch { checkNow() }

    /** Pins the state, so tests don't depend on the real connection; null resumes probing. */
    fun pin(value: Boolean?) {
        pinned = value
        checkNow()
        firstCheck.complete(Unit)
    }

    private fun probe(): Boolean {
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
        // No Wi-Fi or cable at all: don't wait for a connect timeout
        if (interfaces.none { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }) return false
        return probes.any { (host, port) ->
            runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 1_500) } }.isSuccess
        }
    }
}
