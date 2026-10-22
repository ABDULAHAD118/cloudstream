package com.lagradost.cloudstream.mac

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The extension this build ships with is installed once, and stays removed if the user removes it. */
class BundledExtensionTest {
    @Test
    fun seededOnce() {
        val dir = Files.createTempDirectory("cs-bundled").toFile()
        try {
            val pluginDir = File(dir, "plugins")
            val marker = File(dir, "bundled-extension")

            val first = Extensions.seedBundled(pluginDir, marker, SavedState())!!
            assertTrue(File(pluginDir, "CNC Verse.cs3").isFile, "the bundled extension was not copied")
            assertEquals(listOf("CNC Verse"), first.plugins.map { it.internalName })
            assertEquals("CNC Verse", first.plugins.first().name)
            assertEquals(115, first.plugins.first().version)
            assertEquals(listOf("CNC Repo(All Language)"), first.repos.map { it.name })

            // Second launch: nothing to do
            assertNull(Extensions.seedBundled(pluginDir, marker, first))
            // Removed in the UI: not put back on the next launch
            File(pluginDir, "CNC Verse.cs3").delete()
            assertNull(Extensions.seedBundled(pluginDir, marker, first))
            assertTrue(!File(pluginDir, "CNC Verse.cs3").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A profile that already installed it from the repo keeps its own (possibly newer) entry. */
    @Test
    fun keepsAnExistingInstall() {
        val dir = Files.createTempDirectory("cs-bundled").toFile()
        try {
            val existing = SavedState(plugins = listOf(InstalledPlugin("CNC Verse", "CNC Verse", 200, "https://example.com/cnc.json")))
            val seeded = Extensions.seedBundled(File(dir, "plugins"), File(dir, "marker"), existing)!!
            assertEquals(listOf(200), seeded.plugins.map { it.version })
            assertEquals("https://example.com/cnc.json", seeded.plugins.first().repoUrl)
        } finally {
            dir.deleteRecursively()
        }
    }
}
