package com.labteto.dshmobile.observability

import android.util.Log
import com.labteto.dshmobile.BuildConfig
import java.io.File
import java.util.UUID
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class AppLogEntry(
    val timestampMillis: Long,
    val level: String,
    val tag: String,
    val message: String,
    val throwableType: String? = null,
    val throwableMessage: String? = null,
    val appVersion: String? = null,
    val appVersionCode: Int? = null,
    val processInstanceId: String? = null,
    val processStartedAtMillis: Long? = null,
)

/**
 * Process-wide logging facade.
 *
 * Android logcat remains the immediate sink. A bounded in-memory tail is kept for the current
 * process and a sanitized app-private tail is persisted so diagnostics remain useful after a crash,
 * update or process restart.
 */

internal fun createAppLogPersistenceExecutor(maxQueued: Int = 1_024): ThreadPoolExecutor {
    require(maxQueued > 0) { "日志持久化队列容量必须大于 0" }
    return ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxQueued),
        { runnable -> Thread(runnable, "777-app-log").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
}

object AppLog {
    private const val MAX_ENTRIES = 200
    private const val MAX_EXPORT_ENTRIES = 800
    private const val MAX_PERSISTED_BYTES = 2L * 1024L * 1024L
    private const val ROTATED_ENTRIES = 400
    private val lock = Any()
    private val persistenceLock = Any()
    private val entries = ArrayDeque<AppLogEntry>(MAX_ENTRIES)
    private val persistenceExecutor = createAppLogPersistenceExecutor()
    private val processInstanceId = UUID.randomUUID().toString().substringBefore('-')
    private val processStartedAtMillis = System.currentTimeMillis()

    @Volatile
    private var persistentFile: File? = null

    fun configurePersistence(file: File) {
        persistentFile = file
        runCatching { file.parentFile?.mkdirs() }
    }

    fun debug(tag: String, message: String) {
        record("D", tag, message, null)
        runCatching { Log.d(tag, message) }
    }

    fun info(tag: String, message: String) {
        record("I", tag, message, null)
        runCatching { Log.i(tag, message) }
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        record("W", tag, message, throwable)
        runCatching {
            if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        record("E", tag, message, throwable)
        runCatching {
            if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        }
    }

    fun snapshot(): List<AppLogEntry> = synchronized(lock) { entries.toList() }

    /** Includes the sanitized tail from previous processes and de-duplicates current-process rows. */
    fun exportSnapshot(): List<AppLogEntry> {
        val merged = readPersisted() + snapshot()
        return merged
            .distinctBy {
                listOf(
                    it.timestampMillis.toString(),
                    it.level,
                    it.tag,
                    it.message,
                    it.throwableType.orEmpty(),
                    it.throwableMessage.orEmpty(),
                    it.appVersion.orEmpty(),
                    it.appVersionCode?.toString().orEmpty(),
                    it.processInstanceId.orEmpty(),
                    it.processStartedAtMillis?.toString().orEmpty(),
                ).joinToString("\u0000")
            }
            .sortedBy(AppLogEntry::timestampMillis)
            .takeLast(MAX_EXPORT_ENTRIES)
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
        val file = persistentFile ?: return
        persistenceExecutor.execute {
            synchronized(persistenceLock) {
                runCatching { if (file.exists()) file.writeText("") }
            }
        }
    }

    private fun record(level: String, tag: String, message: String, throwable: Throwable?) {
        val entry = AppLogEntry(
            timestampMillis = System.currentTimeMillis(),
            level = level,
            tag = tag.take(64),
            message = message.take(2_000),
            throwableType = throwable?.javaClass?.simpleName,
            throwableMessage = throwable?.message?.take(1_000),
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            processInstanceId = processInstanceId,
            processStartedAtMillis = processStartedAtMillis,
        )
        synchronized(lock) {
            while (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(entry)
        }
        persistAsync(
            entry.copy(
                message = sanitizeDiagnosticText(entry.message),
                throwableMessage = entry.throwableMessage?.let(::sanitizeDiagnosticText),
            ),
        )
    }

    private fun persistAsync(entry: AppLogEntry) {
        val file = persistentFile ?: return
        persistenceExecutor.execute {
            synchronized(persistenceLock) {
                runCatching {
                    file.parentFile?.mkdirs()
                    rotateIfNeeded(file)
                    file.appendText(encodeEntry(entry) + "\n")
                }
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_PERSISTED_BYTES) return
        val tail = file.useLines { sequence -> sequence.toList().takeLast(ROTATED_ENTRIES) }
        file.writeText(tail.joinToString(separator = "\n", postfix = if (tail.isEmpty()) "" else "\n"))
    }

    private fun readPersisted(): List<AppLogEntry> {
        val file = persistentFile ?: return emptyList()
        return synchronized(persistenceLock) {
            runCatching {
                if (!file.isFile) return@runCatching emptyList()
                file.useLines { lines ->
                    lines.mapNotNull(::decodeEntry).toList().takeLast(MAX_EXPORT_ENTRIES)
                }
            }.getOrDefault(emptyList())
        }
    }

    private fun encodeEntry(entry: AppLogEntry): String = listOf(
        entry.timestampMillis.toString(),
        entry.level,
        encode(entry.tag),
        encode(entry.message),
        encode(entry.throwableType.orEmpty()),
        encode(entry.throwableMessage.orEmpty()),
        encode(entry.appVersion.orEmpty()),
        entry.appVersionCode?.toString().orEmpty(),
        encode(entry.processInstanceId.orEmpty()),
        entry.processStartedAtMillis?.toString().orEmpty(),
    ).joinToString("\t")

    private fun decodeEntry(line: String): AppLogEntry? {
        val parts = line.split('\t')
        if (parts.size != 6 && parts.size != 10) return null
        return runCatching {
            AppLogEntry(
                timestampMillis = parts[0].toLong(),
                level = parts[1],
                tag = decode(parts[2]),
                message = decode(parts[3]),
                throwableType = decode(parts[4]).takeIf(String::isNotBlank),
                throwableMessage = decode(parts[5]).takeIf(String::isNotBlank),
                appVersion = parts.getOrNull(6)?.let(::decode)?.takeIf(String::isNotBlank),
                appVersionCode = parts.getOrNull(7)?.toIntOrNull(),
                processInstanceId = parts.getOrNull(8)?.let(::decode)?.takeIf(String::isNotBlank),
                processStartedAtMillis = parts.getOrNull(9)?.toLongOrNull(),
            )
        }.getOrNull()
    }

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
}

internal fun sanitizeDiagnosticText(value: String): String {
    var result = value
    DIAGNOSTIC_SECRET_PATTERNS.forEach { pattern ->
        result = pattern.replace(result) { match ->
            val prefix = match.groups[1]?.value.orEmpty()
            prefix + "<redacted>"
        }
    }
    return result
}

private val DIAGNOSTIC_SECRET_PATTERNS = listOf(
    Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+"),
    Regex("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)\\s*[:=]\\s*[\\\"']?)[^\\s,\\\"'}]+"),
    Regex("(?i)()\\b(?:github_pat|ghp|gho|ghu|ghs)_[A-Za-z0-9_]+\\b"),
)
