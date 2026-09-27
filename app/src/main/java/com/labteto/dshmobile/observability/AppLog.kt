package com.labteto.dshmobile.observability

import android.util.Log
import java.util.ArrayDeque

data class AppLogEntry(
    val timestampMillis: Long,
    val level: String,
    val tag: String,
    val message: String,
    val throwableType: String? = null,
    val throwableMessage: String? = null,
)

/**
 * Process-wide logging facade.
 *
 * Android logcat remains the immediate sink. A bounded in-memory tail is kept as the stable
 * observability seam for diagnostics/export without spreading android.util.Log across the codebase.
 * Only the throwable type/message are retained here; full stack traces stay in logcat.
 */
object AppLog {
    private const val MAX_ENTRIES = 200
    private val lock = Any()
    private val entries = ArrayDeque<AppLogEntry>(MAX_ENTRIES)

    fun debug(tag: String, message: String) {
        record("D", tag, message, null)
        Log.d(tag, message)
    }

    fun info(tag: String, message: String) {
        record("I", tag, message, null)
        Log.i(tag, message)
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        record("W", tag, message, throwable)
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        record("E", tag, message, throwable)
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
    }

    fun snapshot(): List<AppLogEntry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private fun record(level: String, tag: String, message: String, throwable: Throwable?) {
        val entry = AppLogEntry(
            timestampMillis = System.currentTimeMillis(),
            level = level,
            tag = tag.take(64),
            message = message.take(2_000),
            throwableType = throwable?.javaClass?.simpleName,
            throwableMessage = throwable?.message?.take(1_000),
        )
        synchronized(lock) {
            while (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(entry)
        }
    }
}
