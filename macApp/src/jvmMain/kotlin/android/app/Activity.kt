package android.app

import android.content.Context

/** Stand-in for Android's Activity: extensions read their settings through `CommonActivity.activity` when they load. */
open class Activity : Context()
