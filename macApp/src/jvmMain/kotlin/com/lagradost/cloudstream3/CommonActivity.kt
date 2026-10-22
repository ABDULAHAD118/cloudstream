package com.lagradost.cloudstream3

import android.app.Activity
import com.lagradost.cloudstream.mac.Toasts

/** Desktop twin of the app module's CommonActivity, for the toast helpers extensions call. Signatures match Android. */
object CommonActivity {
    // Some extensions (CNC Verse's Live ones) read their SharedPreferences from it in their constructor
    val activity: Activity? = Activity()

    fun showToast(message: String?, duration: Int? = null) = Toasts.show(message)
    fun showToast(message: Int, duration: Int? = null) = Toasts.show("Message $message") // string resources don't exist here
}
