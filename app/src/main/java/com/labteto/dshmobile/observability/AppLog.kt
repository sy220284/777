package com.labteto.dshmobile.observability

import android.util.Log
import android.util.AtomicFile
import com.labteto.dshmobile.BuildConfig
import java.io.File
import java.util.UUID
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
    val throwableCauseChain: String? = null,
    val eventId: String? = null,
)

/**
 * Process-wide logging facade.
 *
 * Android logcat remains the immediate sink. A bounded in-memory tail is kept for the current
 * process and a sanitized app-private tail is persisted so diagnostics remain useful after a crash,
 * update or process restart.
 */

data class AppLogExport(
    val entries: List<AppLogEntry>,
    val malformedRows: Int = 0,
    val readFailure: String? = null,
    val pendingRows: Int = 0,
    val overflowRows: Long = 0,
    val writeFailure: String? = null,
    val truncated: Boolean = false,
    val emergencyRows: Int = 0,
    val emergencyFailure: String? = null,
) {
    val integrity: String get() = buildList {
        if (malformedRows > 0) add("损坏行=$malformedRows")
        readFailure?.let { add("读取失败=$it") }
        if (pendingRows > 0) add("待写入=$pendingRows（已包含在报告中）")
        if (overflowRows > 0) add("拥塞丢弃=$overflowRows")
        writeFailure?.let { add("写入失败=$it") }
        if (truncated) add("超过近期导出容量，已截取最新记录")
        if (emergencyRows > 0) add("崩溃现场待恢复=$emergencyRows")
        emergencyFailure?.let { add("崩溃现场读取失败=$it") }
    }.let { if (it.isEmpty()) "正常" else it.joinToString("；") }
}

object AppLog {
    private const val MAX_ENTRIES = 200
    private const val MAX_EXPORT_ENTRIES = 2_000
    private const val MAX_PERSISTED_BYTES = 8L * 1024L * 1024L
    private const val ROTATED_ENTRIES = 1_500
    private const val MAX_ROOT_CAUSE_ANCHORS = 32
    private val lock = Any()
    private val persistenceLock = Any()
    private val entries = ArrayDeque<AppLogEntry>(MAX_ENTRIES)
    private data class QueuedEntry(val generation: Long, val entry: AppLogEntry)
    private val persistenceGeneration = AtomicLong(0L)
    private val eventSequence = AtomicLong(0L)
    private val emergencyLock = Any()
    private val persistenceExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "777-app-log").apply { isDaemon = true }
    }
    private val pendingWrites = DiagnosticWriteQueue(
        executor = persistenceExecutor,
        writer = { batch: List<QueuedEntry> ->
            synchronized(persistenceLock) {
                val generation = persistenceGeneration.get()
                persistBatch(batch.filter { it.generation == generation }.map { it.entry })
            }
        },
        important = { pending: QueuedEntry -> pending.entry.isDiagnosticFailure() },
        scheduleRetry = { work -> persistenceExecutor.schedule(work, 15L, TimeUnit.SECONDS) },
    )
    private val processInstanceId = UUID.randomUUID().toString()
    private val processStartedAtMillis = System.currentTimeMillis()

    @Volatile
    private var persistentFile: File? = null

    @Volatile
    private var crashHandlerInstalled = false

    fun configurePersistence(file: File) {
        persistentFile = file
        runCatching { file.parentFile?.mkdirs() }
        // Recovery runs on the writer thread before subsequent new log batches.
        // Startup never waits for disk repair or an unreadable emergency file.
        runCatching {
            persistenceExecutor.execute {
                runCatching { recoverEmergency(file) }.onFailure { error ->
                    runCatching {
                        Log.e("AppLog", "Crash recovery failed", sanitizedThrowableForLogcat(error))
                    }
                }
            }
        }
    }

    private data class EmergencyRead(
        val entries: List<AppLogEntry> = emptyList(),
        val malformedRows: Int = 0,
        val failure: String? = null,
    )

    private fun emergencyFile(file: File): AtomicFile =
        AtomicFile(File(file.parentFile, "app-crash-recovery.tsv"))

    private fun readEmergency(file: File): EmergencyRead {
        val atomicFile = emergencyFile(file)
        val base = atomicFile.baseFile
        if (!base.exists() && !File(base.path + ".bak").exists()) return EmergencyRead()
        return try {
            val entries = mutableListOf<AppLogEntry>()
            var malformed = 0
            atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { line ->
                    val item = decodeEntry(line)
                    if (item == null) malformed++ else entries.add(item)
                }
            }
            EmergencyRead(entries, malformed)
        } catch (error: Exception) {
            EmergencyRead(failure = error.javaClass.simpleName)
        }
    }

    private fun recoverEmergency(file: File) = synchronized(persistenceLock) {
        synchronized(emergencyLock) {
            val recovery = readEmergency(file)
            if (recovery.failure != null || recovery.malformedRows > 0) {
                throw java.io.IOException(
                    "Invalid crash recovery: read=${recovery.failure} malformed=${recovery.malformedRows}",
                )
            }
            if (recovery.entries.isNotEmpty()) {
                val persistedIds = readPersistedUnsafe().entries.mapNotNull { it.eventId }.toHashSet()
                persistBatch(recovery.entries.filter { it.eventId == null || it.eventId !in persistedIds })
                // Delete only after a successful append; event IDs also make later
                // recovery attempts idempotent at export time.
                emergencyFile(file).delete()
            }
        }
    }

    private fun persistEmergency(crash: AppLogEntry) {
        val file = persistentFile ?: return
        // A fatal handler must never wait for a regular disk batch, rotate a large log,
        // or scan the full durable history. Save bounded and already-redacted evidence.
        val recent = snapshot()
        val queued = pendingWrites.snapshot().outstanding
            .filter { it.generation == persistenceGeneration.get() }
            .map { it.entry }
        val critical = (recent + queued).filter(AppLogEntry::isDiagnosticFailure).takeLast(32)
        val evidence = deduplicateDiagnosticEntries(critical + recent.takeLast(24) + listOf(crash))
            .sortedBy(AppLogEntry::timestampMillis)
        synchronized(emergencyLock) {
            val target = emergencyFile(file)
            val output = target.startWrite()
            try {
                output.write(evidence.joinToString("\n", postfix = "\n", transform = ::encodeEntry)
                    .toByteArray(Charsets.UTF_8))
                target.finishWrite(output)
            } catch (error: Exception) {
                target.failWrite(output)
                throw error
            }
        }
    }

    /** Preserve fatal failures before Android terminates the process. Delegate to its original handler. */
    @Synchronized
    fun installUnhandledExceptionLogger() {
        if (crashHandlerInstalled) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                record(
                    "E",
                    "UncaughtException",
                    "operation=uncaught_exception thread=${sanitizeDiagnosticText(thread.name).take(80)}",
                    throwable,
                    synchronous = true,
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // Never consume a fatal crash when Android has no previous default handler.
                // Clear our handler before invoking ThreadGroup to avoid recursive delegation.
                Thread.setDefaultUncaughtExceptionHandler(null)
                thread.threadGroup?.uncaughtException(thread, throwable)
            }
        }
        crashHandlerInstalled = true
    }

    /** A stable operation marker connects caught errors to the user action that triggered them. */
    fun failure(
        tag: String,
        operation: String,
        throwable: Throwable,
        sessionId: String? = null,
        requestId: String? = null,
        runId: String? = null,
    ) {
        val identity = buildString {
            append("operation=").append(operation.take(100))
            sessionId?.let { append(" session_id=").append(it.take(128)) }
            requestId?.let { append(" request_id=").append(it.take(128)) }
            runId?.let { append(" run_id=").append(it.take(128)) }
        }
        error(tag, identity, throwable)
    }

    fun debug(tag: String, message: String) {
        val safeMessage = sanitizeDiagnosticText(message)
        record("D", tag, safeMessage, null)
        runCatching { Log.d(tag, safeMessage) }
    }

    fun info(tag: String, message: String) {
        val safeMessage = sanitizeDiagnosticText(message)
        record("I", tag, safeMessage, null)
        runCatching { Log.i(tag, safeMessage) }
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        val safeMessage = sanitizeDiagnosticText(message)
        record("W", tag, safeMessage, throwable)
        val safeThrowable = throwable?.let(::sanitizedThrowableForLogcat)
        runCatching {
            if (safeThrowable == null) Log.w(tag, safeMessage) else Log.w(tag, safeMessage, safeThrowable)
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val safeMessage = sanitizeDiagnosticText(message)
        record("E", tag, safeMessage, throwable)
        val safeThrowable = throwable?.let(::sanitizedThrowableForLogcat)
        runCatching {
            if (safeThrowable == null) Log.e(tag, safeMessage) else Log.e(tag, safeMessage, safeThrowable)
        }
    }

    fun snapshot(): List<AppLogEntry> = synchronized(lock) { entries.toList() }

    /** Snapshot disk + pending + in-flight rows under one disk lock; no queue-drain wait. */
    fun exportForDiagnostics(): AppLogExport = synchronized(persistenceLock) {
        val persisted = readPersistedUnsafe()
        val emergency = synchronized(emergencyLock) { persistentFile?.let(::readEmergency) ?: EmergencyRead() }
        val pending = pendingWrites.snapshot()
        val currentPending = pending.outstanding.filter { it.generation == persistenceGeneration.get() }
        val merged = deduplicateDiagnosticEntries(
            persisted.entries + emergency.entries + currentPending.map { it.entry } + snapshot(),
        ).sortedWith(compareBy(AppLogEntry::timestampMillis, { it.eventId.orEmpty() }))
        val roots = LinkedHashMap<String, AppLogEntry>()
        merged.forEach { entry ->
            if (entry.isDiagnosticFailure() && roots.size < MAX_ROOT_CAUSE_ANCHORS) {
                roots.putIfAbsent(rootCauseKey(entry), entry)
            }
        }
        val retained = deduplicateDiagnosticEntries(
            roots.values.toList() + merged.takeLast(MAX_EXPORT_ENTRIES - roots.size),
        ).sortedWith(compareBy(AppLogEntry::timestampMillis, { it.eventId.orEmpty() }))
        AppLogExport(
            entries = retained,
            malformedRows = persisted.malformedRows,
            readFailure = persisted.failure,
            pendingRows = currentPending.size,
            overflowRows = pending.overflow,
            writeFailure = pending.writeError,
            truncated = persisted.truncated || merged.size > MAX_EXPORT_ENTRIES,
            emergencyRows = emergency.entries.size,
            emergencyFailure = emergency.failure ?: if (emergency.malformedRows > 0) "损坏行=${emergency.malformedRows}" else null,
        )
    }

    fun exportSnapshot(): List<AppLogEntry> = exportForDiagnostics().entries

    fun clear() {
        synchronized(persistenceLock) {
            // Invalidate batches already picked up by the writer but not yet committed.
            persistenceGeneration.incrementAndGet()
            pendingWrites.clearPending()
            synchronized(lock) { entries.clear() }
            persistentFile?.let { file ->
                runCatching { if (file.exists()) file.writeText("") }
            }
        }
    }

    private fun record(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable?,
        synchronous: Boolean = false,
    ) {
        val generation = persistenceGeneration.get()
        val entry = AppLogEntry(
            timestampMillis = System.currentTimeMillis(),
            level = level,
            tag = tag.take(64),
            message = sanitizeDiagnosticText(message).take(2_000),
            throwableType = throwable?.javaClass?.simpleName,
            throwableMessage = throwable?.message?.let(::sanitizeDiagnosticText)?.take(1_000),
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            processInstanceId = processInstanceId,
            processStartedAtMillis = processStartedAtMillis,
            throwableCauseChain = throwable?.let(::summarizeCauseChain),
            eventId = "$processInstanceId:${eventSequence.incrementAndGet()}",
        )
        synchronized(lock) {
            if (generation == persistenceGeneration.get()) {
                while (entries.size >= MAX_ENTRIES) entries.removeFirst()
                entries.addLast(entry)
            }
        }
        if (synchronous) {
            runCatching { persistEmergency(entry) }.onFailure { failure ->
                runCatching {
                    Log.e("AppLog", "Fatal diagnostic persistence failed", sanitizedThrowableForLogcat(failure))
                }
            }
        } else if (persistentFile != null) {
            pendingWrites.offer(QueuedEntry(generation, entry))
        }
    }

    private fun summarizeCauseChain(error: Throwable): String =
        generateSequence(error) { it.cause }.take(5).joinToString(" <- ") { current ->
            val kind = current.javaClass.simpleName.take(80)
            val reason = current.message?.let(::sanitizeDiagnosticText)?.replace("\n", " ")?.take(200)
            if (reason.isNullOrBlank()) kind else "$kind: $reason"
        }.take(1_000)

    private fun persistBatch(batch: List<AppLogEntry>) {
        if (batch.isEmpty()) return
        val file = persistentFile ?: return
        synchronized(persistenceLock) {
            try {
                file.parentFile?.mkdirs()
                rotateIfNeeded(file)
                file.appendText(batch.joinToString(separator = "\n", postfix = "\n", transform = ::encodeEntry))
            } catch (failure: Exception) {
                runCatching { Log.e("AppLog", "Diagnostic batch persistence failed", sanitizedThrowableForLogcat(failure)) }
                throw failure
            }
        }
    }

    private fun rootCauseKey(entry: AppLogEntry): String =
        listOf(
            entry.tag,
            entry.throwableType.orEmpty(),
            entry.message.substringAfter("operation=", "").substringBefore(' '),
            entry.throwableCauseChain.orEmpty().substringBefore(':'),
        ).joinToString("|")

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_PERSISTED_BYTES) return
        val tail = ArrayDeque<String>(ROTATED_ENTRIES)
        val anchors = LinkedHashMap<String, String>()
        file.useLines { lines ->
            lines.forEach { line ->
                val decoded = decodeEntry(line)
                if (decoded != null && decoded.isDiagnosticFailure() &&
                    anchors.size < MAX_ROOT_CAUSE_ANCHORS
                ) {
                    anchors.putIfAbsent(rootCauseKey(decoded), line)
                }
                if (tail.size >= ROTATED_ENTRIES) tail.removeFirst()
                tail.addLast(line)
            }
        }
        // Carry one earliest example per failure class across rotations, alongside
        // the recent timeline. Repeated ordinary events cannot evict the first failure.
        val kept = (anchors.values + tail.toList()).distinct()
        file.writeText(kept.joinToString(separator = "\n", postfix = if (kept.isEmpty()) "" else "\n"))
    }

    private data class PersistedRead(
        val entries: List<AppLogEntry>,
        val malformedRows: Int,
        val failure: String?,
        val truncated: Boolean,
    )

    private fun readPersistedUnsafe(): PersistedRead {
        val file = persistentFile ?: return PersistedRead(emptyList(), 0, null, false)
        val tail = ArrayDeque<AppLogEntry>(MAX_EXPORT_ENTRIES)
        val anchors = LinkedHashMap<String, AppLogEntry>()
        var totalRows = 0
        var malformed = 0
        var failure: String? = null
        try {
            if (file.isFile) {
                file.useLines { lines ->
                    lines.forEach { line ->
                        val entry = decodeEntry(line)
                        if (entry == null) {
                            malformed++
                        } else {
                            totalRows++
                            if (entry.isDiagnosticFailure() && anchors.size < MAX_ROOT_CAUSE_ANCHORS) {
                                anchors.putIfAbsent(rootCauseKey(entry), entry)
                            }
                            if (tail.size >= MAX_EXPORT_ENTRIES) tail.removeFirst()
                            tail.addLast(entry)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            failure = error.javaClass.simpleName
        }
        return PersistedRead(
            deduplicateDiagnosticEntries(anchors.values.toList() + tail.toList()),
            malformed, failure, totalRows > MAX_EXPORT_ENTRIES,
        )
    }

    internal fun encodeEntry(entry: AppLogEntry): String = listOf(
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
        encode(entry.throwableCauseChain.orEmpty()),
        encode(entry.eventId.orEmpty()),
    ).joinToString("\t")

    internal fun decodeEntry(line: String): AppLogEntry? {
        val parts = line.split('\t')
        if (parts.size != 6 && parts.size != 10 && parts.size != 11 && parts.size != 12) return null
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
                throwableCauseChain = parts.getOrNull(10)?.let(::decode)?.takeIf(String::isNotBlank),
                eventId = parts.getOrNull(11)?.let(::decode)?.takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
}

/** New records have stable IDs; older 6/10/11-column records use structural identity. */
internal fun deduplicateDiagnosticEntries(entries: List<AppLogEntry>): List<AppLogEntry> =
    entries.distinctBy { it.eventId ?: it.copy(eventId = null) }

/** A warning with an explicit failure marker is still a real diagnostic failure. */
internal fun AppLogEntry.isDiagnosticFailure(): Boolean {
    if (level == "E" || throwableType != null) return true
    if (level != "W") return false
    if (message.contains("code=TASK_CANCELLED") || message.contains("operation=execute_tool_cancelled")) return false
    return message.contains("failure=true") || message.contains("status=failed") ||
        message.contains("status=corrupt") || message.contains("error_code=") ||
        message.contains("失败") || message.contains("异常")
}

private fun sanitizedThrowableForLogcat(throwable: Throwable): Throwable {
    val safeMessage = sanitizeDiagnosticText(throwable.message.orEmpty()).ifBlank { null }
    return RuntimeException(safeMessage).also { sanitized ->
        sanitized.stackTrace = throwable.stackTrace
    }
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
    Regex("(?i)([?&](?:access_token|refresh_token|api_key|token|key)=)[^&#\\s]+"),
    Regex("(?i)((?:set-cookie|cookie)\\s*[:=]\\s*)[^\\r\\n]+"),
    Regex("(?i)()\\bsk-[A-Za-z0-9_-]{12,}\\b"),
    Regex("(?i)()/(?:data/(?:user/\\d+|data)|storage/emulated/\\d+|sdcard)/[^\\s,;]+"),
)
