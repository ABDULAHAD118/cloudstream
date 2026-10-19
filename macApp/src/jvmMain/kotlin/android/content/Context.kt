package android.content

import com.lagradost.cloudstream.mac.Preferences

/**
 * Stand-in for Android's Context so extensions compiled against `Plugin.load(Context)` link on the JVM.
 * Only the non-UI calls extensions make while loading and scraping are real; UI ones are left out on purpose.
 */
open class Context {
    open fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = Preferences.named(name ?: "default")
    open fun getApplicationContext(): Context = this
    // Extensions sometimes key settings off the host app's package
    open fun getPackageName(): String = "com.lagradost.cloudstream3"
}
