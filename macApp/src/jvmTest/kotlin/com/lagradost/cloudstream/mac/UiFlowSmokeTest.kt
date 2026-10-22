package com.lagradost.cloudstream.mac

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Drives the real UI through every screen against the official extension repo, from a clean profile:
 * repositories, installing and removing extensions, home, search, a movie and a series in the built-in player
 * (play/pause, seek, speed, next episode, back). Saves a screenshot per step. Run with CS_SMOKE=1.
 */
@OptIn(ExperimentalTestApi::class)
class UiFlowSmokeTest {
    private val shots = File("build/ui-shots").apply { deleteRecursively(); mkdirs() }
    private var nudge = false

    // A dialog is a second root; capture the topmost one
    private fun ComposeUiTest.shot(name: String) =
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(shots, "$name.png"))

    private fun ComposeUiTest.waitForText(text: String, timeout: Long = 60_000) =
        waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.texts(): List<String> = onAllNodes(SemanticsMatcher("any text") { true })
        .fetchSemanticsNodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    // The player shows "position / length", e.g. "1:11 / 10:34" (hours appear as h:mm:ss)
    private val clock = Regex("""^(?:(\d+):)?(\d+):(\d\d) / """)

    /** Player position in seconds, read from its "position / length" label. */
    private fun ComposeUiTest.position(): Int? {
        // Moving the mouse keeps the auto-hiding controls on screen
        nudge = !nudge
        onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(Offset(if (nudge) 200f else 210f, 300f)) }
        return texts().firstNotNullOfOrNull { clock.find(it) }?.let { m ->
            (m.groupValues[1].toIntOrNull() ?: 0) * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
        }
    }

    /** Waits until playback has really advanced 3 s, whatever position it started from. */
    private fun ComposeUiTest.waitForPlayback(name: String) {
        var first: Int? = null
        try {
            waitUntil(timeoutMillis = 90_000) {
                val now = position()
                if (first == null) first = now
                now != null && now >= first!! + 3
            }
        } finally { shot(name) }
    }

    /**
     * Scrolls the first row to its end like a user, clicks "Load more" and waits for titles that weren't there.
     * Returns false when that row has no further page.
     */
    private fun ComposeUiTest.loadMoreInFirstRow(): Boolean {
        val row = onAllNodes(hasScrollToIndexAction() and hasAnyAncestor(hasScrollToIndexAction())).onFirst()
        // Rows fetch their next page when scrolled near the end, as on Android
        val before = texts().toSet()
        var i = 0
        while (runCatching { row.performScrollToIndex(i + 5) }.isSuccess) i += 5
        return runCatching { waitUntil(timeoutMillis = 60_000) { texts().any { it !in before } } }.isSuccess
    }

    /** The title page's Play button (white, with an icon), as opposed to the player's play icon. */
    private fun ComposeUiTest.waitPlayButton() =
        waitUntil(timeoutMillis = 60_000) { onAllNodes((hasText("Play") or hasText("Resume")) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }

    /** Clicks the Install button in the plugin row that contains [name]. */
    private fun ComposeUiTest.install(name: String) {
        onNode(hasText("Install") and hasClickAction() and hasAnyAncestor(hasTestTag("plugin-$name"))).performClick()
        try {
            // Done when it's saved and the busy bar ("Installing X…") has gone
            waitUntil(timeoutMillis = 120_000) {
                Extensions.state.plugins.any { it.internalName == name } && texts().none { it.startsWith("Installing") && it.endsWith("…") }
            }
        } catch (e: Throwable) { shot("fail-install-$name"); println("TEXTS ${texts()}"); throw e }
    }

    @Test
    fun everyScreen() {
        if (System.getenv("CS_SMOKE") != "1") return
        // Back to a first launch: only the default official repo, nothing installed (other tests share the profile)
        Extensions.state.plugins.forEach { Extensions.uninstall(it.internalName) }
        Extensions.state.repos.forEach { Extensions.removeRepo(it.url) }
        runBlocking { Extensions.addRepo(Extensions.OFFICIAL_REPO) }
        Preferences.named("cloudstream_mac").edit().clear().commit() // remembered Home provider, quality cap
        Library.clearHistory(); Library.bookmarks.forEach { Library.setBookmark(it.apiName, it.url, null) }
        Providers.refresh(); Providers.loading = false

        runComposeUiTest(testTimeout = 12.minutes) {
            var start by mutableStateOf(listOf<Screen>(Screen.Home))
            setContent {
                CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
                    androidx.compose.material3.Surface { key(start) { App(start) } }
                }
            }
            waitForText("No extensions yet"); shot("01-first-launch")

            // Repositories: the official repo is there by default; remove it and add it back by URL
            onNodeWithText("Extensions").performClick()
            waitForText("Cloudstream providers repository"); waitForText("extensions ·"); shot("02-extensions-default-repo")
            onAllNodesWithText("Remove").onFirst().performClick()
            waitUntil { Extensions.state.repos.isEmpty() }
            onNodeWithText("Repository URL", substring = true).performTextInput("https://github.com/recloudstream/extensions")
            onAllNodesWithText("Add").onFirst().performClick() // next to the URL field; the community list has more
            waitUntil(timeoutMillis = 60_000) { Extensions.state.repos.any { it.url == Extensions.OFFICIAL_REPO } }
            waitForText("Cloudstream providers repository"); waitForIdle()
            onNodeWithText("Cloudstream providers repository", useUnmergedTree = true).performClick() // its page lists the extensions
            waitForText("InternetArchiveProvider"); shot("03-repo-added-by-github-link")

            // Install two extensions through the UI; Search filters the list
            install("InternetArchiveProvider")
            onNodeWithText("Search extensions").performTextInput("youtube")
            waitUntil { onAllNodesWithText("DailymotionProvider").fetchSemanticsNodes().isEmpty() }
            install("YoutubeProvider")
            onNodeWithText("Search extensions").performTextClearance()
            onNodeWithContentDescription("Back").performClick()
            // Community repos: the built-in list, with the official repo marked as added
            onNodeWithText("Community repos").performClick()
            waitForText("Added")
            onNodeWithText("Search community repositories").performTextInput("netmirror")
            waitForText("NetMirror"); shot("04a-community-repos")
            onNodeWithText("Installed extensions", substring = true).performClick()
            waitForText("Installed extensions (2)"); waitForText("Internet Archive"); shot("04-installed")
            assertTrue(Extensions.loadErrors.isEmpty(), "load errors: ${Extensions.loadErrors}")
            assertTrue(Providers.list.any { it.name == "Internet Archive" } && Providers.list.any { it.name == "YouTube" })

            // Home: rows from a provider's main page
            onNodeWithText("Home").performClick()
            try { waitUntil(timeoutMillis = 90_000) { texts().size >= 6 } } // rail + picker + a row title + cards
            catch (e: Throwable) { shot("fail-home"); println("TEXTS ${texts()}"); throw e }
            waitForIdle(); Thread.sleep(3000); shot("05-home")
            // Load more grows a row; Reload fetches the page again
            if (loadMoreInFirstRow()) shot("05b-home-load-more") else println("SKIP home load more: no next page")
            onAllNodesWithContentDescription("Reload").onFirst().performClick()
            waitUntil(timeoutMillis = 90_000) { texts().size >= 6 }
            waitForIdle(); shot("05c-home-reloaded")
            // The chosen provider survives opening a title and coming back (it used to reset to the first)
            onNodeWithText("Internet Archive").performClick() // the picker shows the current provider
            onNodeWithText("YouTube", substring = true).performClick()
            waitUntil(timeoutMillis = 90_000) { HomeState.loadedFor == "YouTube" && !HomeState.loading && HomeState.rows.isNotEmpty() }
            waitForIdle()
            onAllNodesWithTag("poster").onFirst().performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("Back").performClick()
            waitForText("Home"); waitForIdle()
            assertTrue(onAllNodesWithText("YouTube").fetchSemanticsNodes().isNotEmpty(), "provider reset after going back")
            assertTrue(HomeState.rows.isNotEmpty())
            shot("05d-home-kept-provider")

            // Search across providers
            onAllNodesWithText("Search").onFirst().performClick() // the sidebar item; the screen's button comes later
            onNodeWithText("Search all providers").performTextClearance()
            onNodeWithText("Search all providers").performTextInput("big buck bunny")
            onNodeWithTag("home-search").performImeAction()
            waitForText("Internet Archive"); waitForIdle(); Thread.sleep(3000); shot("06-search")
            assertTrue(loadMoreInFirstRow(), "search row offered no further page")
            shot("06b-search-load-more")

            // A movie in the built-in player. Archive search order drifts, so open a title known to have links
            // Player timing checks run on a YouTube video: Archive's servers stall too often to time seeks reliably
            val youtube = APIHolder.apis.first { it.name == "YouTube" }
            val movie = runBlocking {
                // Some results (Shorts, restricted videos) have no playable links; take one that does
                // The 10-minute Blender film: other hits are Shorts too brief for the seek checks
                youtube.search("big buck bunny blender foundation short film", 1)!!.items.take(8).first { item ->
                    val res = youtube.load(item.url) as? MovieLoadResponse ?: return@first false
                    if ("short" in item.url.lowercase() || item.name.contains("#shorts", true)) return@first false
                    var found = false
                    youtube.loadLinks(res.dataUrl, false, {}, { found = true })
                    found
                }
            }
            start = listOf(Screen.Search, Screen.Result(youtube, movie))
            waitPlayButton(); waitForIdle(); shot("07-movie")
            onAllNodesWithContentDescription("Reload").onFirst().performClick()
            waitPlayButton(); shot("07b-movie-reloaded")
            onNodeWithText("Play").performClick()
            waitForPlayback("08-player-playing")

            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Spacebar) } // pause
            try { waitUntil(timeoutMillis = 20_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() } } catch (e: Throwable) { shot("fail-pause"); throw e }
            shot("09-player-paused")
            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Spacebar) } // resume
            val before = position()!!
            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.DirectionRight) } // +10 s
            try { waitUntil(timeoutMillis = 90_000) { (position() ?: 0) >= before + 12 } } catch (e: Throwable) { shot("fail-seek"); throw e }
            shot("10-player-seeked")
            // Hovering the seek bar shows the frame there, from a second silent player
            onNodeWithTag("seek-bar").performMouseInput { moveTo(Offset(width * 0.6f, height / 2f)) }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithContentDescription("Preview frame", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            shot("10a-seek-preview")
            onNodeWithTag("seek-bar").performMouseInput { moveTo(Offset(width * 0.6f, -400f)) }
            onNodeWithContentDescription("More options").performClick(); shot("10b-player-menu")
            onNodeWithText("Speed").performClick()
            onNodeWithText("2.0x").performClick()
            waitUntil { VideoEngine.shared().getOrThrow().rate == 2f }; shot("11-player-2x")
            onNodeWithContentDescription("Back").performClick()
            waitPlayButton(); shot("12-back-to-movie")

            // Download: every quality is offered; take the smallest, then play it offline from the Downloads tab
            System.setProperty("cloudstream.downloadDir", File("build/test-downloads").absolutePath)
            onNodeWithText("Download").performClick()
            waitUntil(timeoutMillis = 60_000) { onAllNodesWithTag("download-option").fetchSemanticsNodes().size >= 2 }
            shot("12b-download-picker")
            val options = onAllNodes(hasText("Download") and hasClickAction() and hasAnyAncestor(hasTestTag("download-option")))
            options[options.fetchSemanticsNodes().size - 1].performClick()
            onNodeWithText("Downloads").performClick()
            val ours = Downloads.items.first().id // newest first; other tests may have left entries below it
            fun download() = Downloads.items.first { it.id == ours }
            waitUntil(timeoutMillis = 10 * 60_000) { download().status.let { it == DownloadStatus.Done || it == DownloadStatus.Failed } }
            assertTrue(download().status == DownloadStatus.Done, "download failed: ${download().error}")
            waitForText("Show in Finder"); shot("12c-downloads-done")
            onAllNodesWithText("Play").onFirst().performClick()
            waitForPlayback("12d-offline-playback")
            onNodeWithContentDescription("Back").performClick()
            waitForText("Show in Finder")
            onAllNodesWithContentDescription("Delete").onFirst().performClick()
            waitUntil { Downloads.items.none { it.id == ours } }

            // A series: episodes list, then Next episode inside the player
            val series = runBlocking {
                // YouTube labels every hit "Others"; channels load as series, so check what each one loads as
                youtube.search("kurzgesagt", 1)!!.items.take(5).firstOrNull { item ->
                    (youtube.load(item.url) as? TvSeriesLoadResponse)?.episodes?.let { it.size >= 2 } == true
                }
            }
            if (series == null) {
                println("SKIP series: YouTube search returned no channel or playlist with episodes")
            } else {
                start = listOf(Screen.Search, Screen.Result(youtube, series))
                waitUntil(timeoutMillis = 90_000) { onAllNodes(hasTestTag("episode-1")).fetchSemanticsNodes().isNotEmpty() }
                waitForIdle(); shot("13-series-episodes")
                onNodeWithTag("episode-0").performClick()
                waitForPlayback("14-series-episode-1")
                // Quality: 4K picks the 2160p source, 1080p drops back; Reload refetches and resumes
                onNodeWithContentDescription("More options").performClick()
                onNodeWithText("Quality").performClick()
                onNodeWithText("4K").performClick()
                // YouTube's 4K is VP9 + separate audio, which can't seek, so "4K" lands on the best seekable source
                waitUntil(timeoutMillis = 30_000) { texts().any { Regex("· (2160|1440|1080)p$").containsMatchIn(it) } }
                waitForPlayback("14b-series-4k")
                onNodeWithContentDescription("More options").performClick()
                onNodeWithText("Quality").performClick()
                onNodeWithText("1080p").performClick()
                waitUntil(timeoutMillis = 30_000) { texts().any { Regex("· (1080|720|480|360|240|144)p$").containsMatchIn(it) } }
                waitForPlayback("14c-series-1080p")
                onNodeWithContentDescription("More options").performClick()
                onNodeWithText("Reload sources").performClick()
                waitForPlayback("14d-series-reloaded")
                val firstTitle = texts().first { it.startsWith("${series.name} - ") }
                onNodeWithContentDescription("Next episode", substring = true).performClick()
                waitUntil(timeoutMillis = 30_000) { texts().any { it.startsWith("${series.name} - ") && it != firstTitle } }
                waitForPlayback("15-series-next-episode")
                onNodeWithContentDescription("Back").performClick()
            }

            // Uninstall from the Installed list
            start = listOf(Screen.ExtensionsTab)
            onNodeWithText("Installed extensions", substring = true).performClick()
            waitForText("Installed extensions (2)")
            onNode(hasContentDescription("Remove") and hasAnyAncestor(hasTestTag("installed-YoutubeProvider"))).performClick()
            waitForText("Installed extensions (1)"); shot("16-uninstalled")
            assertTrue(Providers.list.none { it.name == "YouTube" })
        }
    }
}
