package com.lagradost.cloudstream.mac

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toAwtImage
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * With the connection pinned off, the screens say so instead of spinning: the offline bar, Home's notice and the
 * community-repos notice, while Downloads and Library stay reachable. Run with CS_SMOKE=1.
 */
@OptIn(ExperimentalTestApi::class)
class OfflineSmokeTest {
    private fun ComposeUiTest.waitForText(text: String, timeout: Long = 30_000) =
        waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private val shots = File("build/ui-shots").apply { mkdirs() }

    private fun ComposeUiTest.shot(name: String) =
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(shots, "$name.png"))

    @Test
    fun offlineScreens() {
        if (System.getenv("CS_SMOKE") != "1") return
        // A first launch with nothing installed: Home has no rows to show, so its own notice must appear
        val providers = Providers.list
        Providers.list = emptyList()
        Providers.loading = false
        Library.clearHistory()
        Network.pin(false)
        try {
            runComposeUiTest {
                setContent {
                    CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
                        androidx.compose.material3.Surface { App(listOf(Screen.Home)) }
                    }
                }
                waitForText("You're offline")
                waitForText("Home needs an internet connection"); shot("offline-home")
                // Downloads still opens
                onNodeWithText("Downloads").performClick()
                waitUntil { onAllNodesWithText("Downloads").fetchSemanticsNodes().size >= 2 } // sidebar + title
                // The community list says it needs the internet; installed extensions stay usable offline
                onNodeWithText("Extensions").performClick()
                onNodeWithText("Community repos").performClick()
                waitForText("the community list needs an internet connection"); shot("offline-community")
                assertTrue(!Network.online)
            }
        } finally {
            Network.pin(null)
            Providers.list = providers
        }
    }
}
