package com.lagradost.cloudstream.mac

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdaterTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Updater.isNewer("1.0.10", "1.0.9")) // not as text, where "1.0.10" < "1.0.9"
        assertTrue(Updater.isNewer("1.1.0", "1.0.99"))
        assertFalse(Updater.isNewer("1.0.3", "1.0.3"))
        assertFalse(Updater.isNewer("1.0.2", "1.0.3"))
    }

    @Test
    fun picksTheFileForThisMachine() {
        assertEquals("-windows.zip", Updater.assetSuffix(windows = true, arch = "amd64"))
        assertEquals("-apple-silicon.dmg", Updater.assetSuffix(windows = false, arch = "aarch64"))
        assertEquals("-intel.dmg", Updater.assetSuffix(windows = false, arch = "x86_64"))
    }
}
