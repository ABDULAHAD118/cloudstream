package com.lagradost.cloudstream3.plugins

import android.content.Context

/** Desktop twin of the app module's Plugin, which almost every extension extends. Keep the member signatures identical. */
abstract class Plugin : BasePlugin() {
    @Throws(Throwable::class)
    open fun load(context: Context) {
        load()
    }

    /** Extension settings UIs are Android views, so the desktop app never opens them. */
    var openSettings: ((context: Context) -> Unit)? = null
}
