package com.lagradost.cloudstream.mac

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** Drives the real UI: Search → result → links, saving a screenshot per step. Run with CS_SMOKE=1. */
@OptIn(ExperimentalTestApi::class)
class UiFlowSmokeTest {
    private val shots = File("build/ui-shots").apply { mkdirs() }

    private fun ComposeUiTest.shot(name: String) =
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots, "$name.png"))

    private fun ComposeUiTest.waitForText(text: String, timeout: Long = 60_000) =
        waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun searchOpenAndListLinks() {
        if (System.getenv("CS_SMOKE") != "1") return
        runBlocking {
            val repo = Extensions.addRepo("https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json")
            Extensions.listPlugins(repo.url).first { it.internalName == "InternetArchiveProvider" }.let { Extensions.install(it, repo.url) }
        }
        Providers.refresh(); Providers.loading = false

        runComposeUiTest(testTimeout = 3.minutes) {
            setContent { CloudStreamTheme(mode = CloudStreamThemeMode.Dark) { androidx.compose.material3.Surface { App() } } }
            waitForText("Internet Archive"); shot("1-home")

            onNodeWithText("Search").performClick()
            onNodeWithText("Search all providers").performTextInput("night of the living dead")
            onAllNodesWithText("Search").onLast().performClick() // the button; the rail item comes first
            waitForText("Internet Archive"); waitForIdle(); Thread.sleep(3000); shot("2-search")

            onAllNodes(hasClickAction() and hasText("Night Of The Living Dead", substring = true, ignoreCase = true)).onLast().performClick()
            waitForText("Play"); shot("3-result")

            onNodeWithText("▶ Play").performClick()
            waitForText("· VIDEO"); shot("4-links")
        }
    }
}
