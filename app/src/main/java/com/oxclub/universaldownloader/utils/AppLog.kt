package com.oxclub.universaldownloader.utils

import android.util.Log
import com.oxclub.universaldownloader.BuildConfig

/**
 * Debug-only logging. Query strings and fragments are stripped from URLs because they can carry
 * secrets, and cookies / authorization headers are never passed to this class.
 */
object AppLog {
    private val URL_RE = Regex("""https?://\S+""")

    fun redact(message: String): String =
        URL_RE.replace(message) { it.value.substringBefore('?').substringBefore('#') }

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, redact(message))
    }

    fun w(tag: String, message: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(tag, redact(message), t)
    }
}
