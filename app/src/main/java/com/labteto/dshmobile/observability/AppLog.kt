package com.labteto.dshmobile.observability

import android.util.Log

/**
 * Process-wide logging facade.
 *
 * Keep Android's logging dependency behind one seam so runtime failures can later be mirrored into
 * an exportable diagnostic buffer without touching every call site.
 */
object AppLog {
    fun debug(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun info(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
    }
}
