package com.lagradost.cloudstream.mac

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import androidx.compose.runtime.*

/** Drives the real UI: Search → title → built-in player → extensions, saving a screenshot per step. Run with CS_SMOKE=1. */
@OptIn(ExperimentalTestApi::class)
class UiFlowSmokeTest {
    private val shots = File("build/ui-shots").apply { mkdirs() }

    private fun ComposeUiTest.shot(name: String) =
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots, "$name.png"))

    private fun ComposeUiTest.waitForText(text: String, timeout: Long = 60_000) =
        waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private val clock = Regex("^(\\d+):(\\d\\d)$")
    private var nudge = false

    /** Player position in seconds: the smallest clock on screen (the other one is the length). */
    private fun ComposeUiTest.position(): Int? {
        // Moving the mouse keeps the auto-hiding controls on screen
        nudge = !nudge
        onRoot().performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(if (nudge) 200f else 210f, 300f)) }
        return onAllNodes(SemanticsMatcher("clock") { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { clock.matches(it.text) } == true
        }).fetchSemanticsNodes().mapNotNull { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.firstNotNullOfOrNull { clock.find(it.text) }
                ?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        }.minOrNull()
    }

    @Test
    fun searchOpenAndListLinks() {
        if (System.getenv("CS_SMOKE") != "1") return
        // Archive search ranking drifts, so find a title that has playable links right now
        val (api, target) = runBlocking {
            val repo = Extensions.addRepo(Extensions.OFFICIAL_REPO)
            Extensions.listPlugins(repo.url).first { it.internalName == "InternetArchiveProvider" }.let { Extensions.install(it, repo.url) }
            val api = APIHolder.apis.first { it.name == "Internet Archive" }
            api to api.search("big buck bunny", 1)!!.items.filter { it.type == TvType.Movie }.take(6).first { item ->
                val res = api.load(item.url) as? MovieLoadResponse ?: return@first false
                var found = false
                api.loadLinks(res.dataUrl, false, {}, { if (it.type == ExtractorLinkType.VIDEO) found = true })
                found
            }
        }
        Providers.refresh(); Providers.loading = false

        runComposeUiTest(testTimeout = 4.minutes) {
            var start by mutableStateOf(listOf<Screen>(Screen.Home))
            setContent {
                CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
                    androidx.compose.material3.Surface { key(start) { App(start) } }
                }
            }
            waitForText("Home"); waitForIdle(); shot("1-home")

            onNodeWithText("Search").performClick()
            onNodeWithText("Search all providers").performTextInput("big buck bunny")
            onAllNodesWithText("Search").onLast().performClick() // the button; the rail item comes first
            waitForText("Internet Archive"); waitForIdle(); Thread.sleep(3000); shot("2-search")

            // Search ordering isn't stable, so open the known-good title the way a click on its card does
            start = listOf(Screen.Search, Screen.Result(api, target))
            waitForText("Play"); waitForIdle(); shot("3-result")

            onNodeWithText("▶ Play").performClick()
            // Real playback in the built-in player: the clock passes 0:03 (moving the mouse keeps the controls up)
            // It has to move 3s past the first value seen, since resume can start it anywhere
            var first: Int? = null
            try { waitUntil(timeoutMillis = 90_000) {
                val now = position()
                if (first == null) first = now
                now != null && now >= first!! + 3
            } } finally { shot("4-player") }
            // → seeks 10s ahead and playback carries on from there
            val before = position()!!
            onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
            waitUntil(timeoutMillis = 60_000) { (position() ?: 0) >= before + 12 }
            shot("4a-player-seeked")
            onNodeWithText("Speed 1.0x").performClick()
            onNodeWithText("2.0x", substring = true).performClick()
            waitForText("Speed 2.0x"); shot("4b-player-2x")
            onNodeWithText("← Back").performClick()

            onNodeWithText("Extensions").performClick()
            waitForText("Reinstall") // the test profile already has every official extension
            waitUntil(timeoutMillis = 60_000) { onAllNodesWithText("Loading…").fetchSemanticsNodes().isEmpty() }
            shot("5-extensions")
            onNodeWithText("Live").performClick(); waitForIdle(); shot("6-extensions-live")
        }
    }
}
