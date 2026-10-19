package com.lagradost.cloudstream3

import com.lagradost.cloudstream.mac.Toasts

/** Desktop twin of the app module's CommonActivity, for the toast helpers extensions call. Signatures match Android. */
object CommonActivity {
    fun showToast(message: String?, duration: Int? = null) = Toasts.show(message)
    fun showToast(message: Int, duration: Int? = null) = Toasts.show("Message $message") // string resources don't exist here
}
