package com.lagradost.cloudstream.mac

import androidx.compose.ui.geometry.Offset
import kotlin.test.assertEquals
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The player's menu staying open past the auto-hide, the centre play/pause flash, double-click ±10 s and Auto
 * quality, against a public multi-quality HLS stream. Run with CS_SMOKE=1.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerControlsSmokeTest {
    private val shots = File("build/ui-shots/player-controls").apply { deleteRecursively(); mkdirs() }
    private val engine get() = VideoEngine.shared().getOrThrow()

    private val hls = object : MainAPI() {
        override var name = "Test HLS"
        override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
            callback(newExtractorLink("Mux", "Mux test", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", ExtractorLinkType.M3U8))
            return true
        }
    }

    private fun ComposeUiTest.shot(name: String) =
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(shots, "$name.png"))

    private fun ComposeUiTest.texts(): List<String> = onAllNodes(SemanticsMatcher("any text") { true })
        .fetchSemanticsNodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun ComposeUiTest.shown(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.moreShown() = onAllNodesWithContentDescription("More options").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun menuFlashSeekAndAutoQuality() {
        if (System.getenv("CS_SMOKE") != "1") return
        Preferences.named("cloudstream_mac").edit().clear().commit() // Auto is the default
        WatchProgress.set(WatchProgress.key(hls, PlayItem("Test", "x")), 0, 0) // start at 0:00, not where the last run stopped
        engine // libVLC takes a while to start; like main(), get it ready before the UI
        println("player-controls: engine ready")
        runComposeUiTest(testTimeout = 4.minutes) {
            setContent {
                CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
                    androidx.compose.material3.Surface { PlayerScreen(hls, listOf(PlayItem("Test", "x")), 0) {} }
                }
            }
            waitUntil(timeoutMillis = 120_000) { engine.playing && engine.time > 3_000 && engine.frame != null }
            println("player-controls: playing")
            val startHeight = engine.frame!!.height
            val root = onAllNodes(isRoot()).onFirst()

            // 1. The menu and its submenu stay open well past the 3 s auto-hide
            root.performMouseInput { moveTo(Offset(200f, 300f)); moveTo(Offset(210f, 300f)) } // the first move only enters; a move brings the auto-hidden controls back
            waitUntil(timeoutMillis = 5_000) { moreShown() }
            onNodeWithContentDescription("More options").performClick()
            mainClock.advanceTimeBy(5_000); waitForIdle()
            assertTrue(shown("Speed") && moreShown(), "menu closed on its own")
            val auto = texts().firstOrNull { Regex("""^Auto \(\d+p\)$""").matches(it) }
            assertTrue(auto != null, "no Auto (…p) label: ${texts()}")
            println("quality label: $auto"); shot("1-menu-after-5s")
            onNodeWithText("Speed").performClick()
            mainClock.advanceTimeBy(5_000); waitForIdle()
            assertTrue(shown("2.0x") && moreShown(), "submenu closed on its own")
            onNodeWithText("1.0x").performClick()
            // Closed: the countdown starts again and hides the bar
            waitUntil(timeoutMillis = 10_000) { !moreShown() } // the 3 s countdown
            // A still pointer doesn't bring them back (they used to blink every 3 s)
            Thread.sleep(4_000); mainClock.advanceTimeBy(4_000); waitForIdle()
            assertTrue(!moreShown(), "controls came back without the mouse moving")

            // 2. A click pauses and flashes the pause icon; another resumes
            val size = root.fetchSemanticsNode().size
            val centre = Offset(size.width / 2f, size.height / 2f)
            waitUntil(timeoutMillis = 30_000) { engine.playing && !engine.buffering } // libVLC ignores a pause mid-buffering
            root.performMouseInput { click(centre) }
            waitUntil(timeoutMillis = 5_000) { !engine.playing }
            shot("2-paused-flash")
            root.performMouseInput { click(centre) }
            waitUntil(timeoutMillis = 10_000) { engine.playing }
            shot("2b-resumed-flash")

            // 3. Double-click: right half +10 s, left half −10 s, with feedback; playback keeps going
            var before = engine.time
            root.performMouseInput { doubleClick(Offset(size.width * 0.75f, size.height / 2f)) }
            shot("3-forward")
            assertTrue(shown("+10s"))
            waitUntil(timeoutMillis = 30_000) { engine.time >= before + 9_000 }
            assertTrue(engine.time < before + 20_000, "skipped too far: ${before / 1000} s -> ${engine.time / 1000} s")
            Thread.sleep(1_000)
            before = engine.time
            root.performMouseInput { doubleClick(Offset(size.width * 0.25f, size.height / 2f)) }
            shot("3b-back")
            assertTrue(shown("−10s"))
            waitUntil(timeoutMillis = 30_000) { engine.time in 0..before - 8_000 }
            assertTrue(engine.playing, "double-click shouldn't pause")

            // 4. Auto climbs from the first variant once the connection proves fast
            waitUntil(timeoutMillis = 90_000) { engine.frame!!.height > startHeight }
            println("quality: Auto went from ${startHeight}p to ${engine.frame!!.height}p")
            root.performMouseInput { moveTo(Offset(220f, 300f)) }
            onNodeWithContentDescription("More options").performClick()
            println("quality label: ${texts().first { it.startsWith("Auto") }}"); shot("4-auto-climbed")

            // 5. A chosen quality stays put: 480p loads the 480p variant
            onNodeWithText("Quality").performClick()
            onNodeWithText("480p").performClick()
            waitUntil(timeoutMillis = 60_000) { engine.playing && engine.frame?.height in 470..496 }
            Thread.sleep(15_000)
            assertTrue(engine.frame?.height in 470..496, "480p didn't stay: ${engine.frame?.height}")
            println("quality: fixed 480p plays ${engine.frame?.width}x${engine.frame?.height}")

            // 6. Skips land in what's already downloaded: time from each skip until playback moves on again
            Thread.sleep(10_000) // let the read-ahead fill
            val waits = listOf(10_000L, 10_000L, -10_000L, -10_000L, 10_000L).map { ms ->
                val target = engine.time + ms
                val start = System.currentTimeMillis()
                engine.skip(ms)
                waitUntil(timeoutMillis = 60_000) { engine.playing && !engine.buffering && engine.time > target + 300 }
                (System.currentTimeMillis() - start).also { Thread.sleep(2_000) }
            }
            println("seek: ms until playing again after ±10 s skips: $waits")
        }
    }

    @Test
    fun resumesInTheMiddle() {
        if (System.getenv("CS_SMOKE") != "1") return
        val item = PlayItem("Resume", "resume")
        WatchProgress.set(WatchProgress.key(hls, item), 300_000, 0) // stopped at 5:00 last time
        engine
        runComposeUiTest(testTimeout = 3.minutes) {
            setContent {
                CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
                    androidx.compose.material3.Surface { PlayerScreen(hls, listOf(item), 0) {} }
                }
            }
            // Used to buffer forever: the resume seek came before libVLC's HLS reader had started
            waitUntil(timeoutMillis = 60_000) { engine.playing && engine.time > 303_000 && engine.frame != null }
            println("resume: playing from ${engine.time / 1000} s")
        }
    }

    // DASH-IF's live simulator: a dynamic manifest whose clock counts from 1970, like the live sports channels
    private val liveChannel = object : MainAPI() {
        override var name = "Test live"
        override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
            callback(newExtractorLink("DASH-IF", "livesim", "https://livesim2.dashif.org/livesim2/testpic_2s/Manifest.mpd", ExtractorLinkType.DASH))
            return true
        }
    }

    /** Plays [api]'s stream as a live item until it's under way, with the controls up. */
    private fun liveItem(api: MainAPI, check: ComposeUiTest.(PlayItem) -> Unit) {
        val item = PlayItem("Channel", api.name, live = true)
        WatchProgress.set(WatchProgress.key(api, item), 0, 0)
        engine
        var show by mutableStateOf(true)
        runComposeUiTest(testTimeout = 3.minutes) {
            setContent {
                CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
                    androidx.compose.material3.Surface { if (show) PlayerScreen(api, listOf(item), 0) {} }
                }
            }
            waitUntil(timeoutMillis = 60_000) { engine.playing && engine.started && engine.frame != null }
            Thread.sleep(2_000)
            onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(Offset(200f, 300f)); moveTo(Offset(220f, 310f)) } // controls up
            waitForIdle()
            check(item)
            show = false; waitForIdle()
        }
    }

    @Test
    fun liveDvrSeekAndGoLive() {
        if (System.getenv("CS_SMOKE") != "1") return
        liveItem(liveChannel) { item ->
            // A live clock counts from 1970, so there is no "0:42 / 1:02" timeline, just the LIVE badge at the edge
            assertTrue(shown("LIVE"), "no LIVE badge: ${texts()}")
            assertTrue(onAllNodesWithText(" / ", substring = true).fetchSemanticsNodes().isEmpty(), "a timeline is shown")
            shot("5-live")

            // The server's window is rewindable: ← goes 10 s back and the badge offers "Go live" from there
            val atEdge = engine.time
            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.DirectionLeft) }
            waitUntil(timeoutMillis = 15_000) { shown("Go live") }
            val rewound = engine.time
            assertTrue(rewound in 1..atEdge - 8_000, "← didn't rewind: ${atEdge / 1000} s -> ${rewound / 1000} s")
            Thread.sleep(3_000) // libVLC must keep the jump, not snap back to the edge
            assertTrue(shown("Go live"), "went back to live on its own")
            assertTrue(engine.time < atEdge - 5_000, "the rewind didn't stick: ${engine.time / 1000} s")
            shot("5b-rewound")

            // "Go live" catches up with the edge again
            onNodeWithText("Go live").performClick()
            waitUntil(timeoutMillis = 60_000) { engine.playing && engine.started && engine.frame != null && engine.time > atEdge }
            onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(Offset(200f, 300f)); moveTo(Offset(220f, 310f)) } // the controls hid
            waitUntil(timeoutMillis = 10_000) { shown("LIVE") }
            shot("5c-back-live")
        }
        assertEquals(0L, WatchProgress.get(WatchProgress.key(liveChannel, PlayItem("Channel", liveChannel.name)))) // nothing saved on leaving
    }

    @Test
    fun recordingLabelledLiveKeepsItsTimeline() {
        if (System.getenv("CS_SMOKE") != "1") return
        // Live extensions' "Highlights" are recordings sent as live: once it plays, the stream has the last word
        liveItem(hls) {
            shot("5b-recording-labelled-live")
            assertTrue(onAllNodesWithText("LIVE").fetchSemanticsNodes().isEmpty(), "a recording got the LIVE badge")
            assertTrue(onAllNodesWithContentDescription("Forward 10 seconds").fetchSemanticsNodes().isNotEmpty(), "no skip button")
        }
    }
}
