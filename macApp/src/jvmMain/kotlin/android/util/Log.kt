package android.util

/** Stand-in for android.util.Log, which many extensions call directly. Same static signatures as Android. */
object Log {
    @JvmStatic fun v(tag: String?, msg: String?): Int = log("V", tag, msg)
    @JvmStatic fun d(tag: String?, msg: String?): Int = log("D", tag, msg)
    @JvmStatic fun i(tag: String?, msg: String?): Int = log("I", tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?): Int = log("W", tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?): Int = log("E", tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = log("E", tag, "$msg\n${tr?.stackTraceToString()}")
    @JvmStatic fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString() ?: ""

    private fun log(level: String, tag: String?, msg: String?): Int {
        println("$level/$tag: $msg")
        return 0
    }
}
