package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.io.readBoundedLine

import com.labteto.dshmobile.harness.session.SessionEventLog
import com.labteto.dshmobile.harness.session.SessionRecovery
import com.labteto.dshmobile.harness.session.SessionRepairResult
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.observability.AppLog
import java.io.File
import java.security.MessageDigest
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Android-facing event adapter; durable storage lives in the native core module. */
class LocalSessionEventLog(
    private val file: File,
    private val json: Json,
    maxBytes: Long = DEFAULT_MAX_BYTES,
    private val sessionId: String? = null,
    private val sqlite: SqliteSessionEventDatabase? = null,
) {
    @Serializable
    data class Event(
        val sequence: Long,
        val type: String,
        val createdAt: Long,
        val data: JsonObject,
    )

    private val delegate = if (sqlite == null) SessionEventLog(
        file = file,
        json = json,
        maxBytes = maxBytes,
        diagnosticSink = { kind, error ->
            AppLog.warn(
                "SessionEventLog",
                "session/event-log-diagnostic kind=$kind file=${file.name}",
                error,
            )
        },
        onSegmentRotated = { LocalSessionArchiveMaintenance.request(file, json) },
    ) else null

    private val sqliteId: String get() = requireNotNull(sessionId) {
        "SQLite event log requires a session identity"
    }

    private fun fileLog(): SessionEventLog = requireNotNull(delegate)

    // The durable log is the only publisher of committed event revisions.
    // Consumers receive the current revision immediately, then each successful append/reset.
    private val mutableChanges = MutableStateFlow(0L)
    internal val changes: StateFlow<Long> = mutableChanges.asStateFlow()

    internal val historyInstanceIdentity: String = java.util.UUID.randomUUID().toString()

    private val checkpoints = LocalSessionProjectionCheckpoints(file, json)
    private var checkpointIdentity: String? = null

    private fun projectionIdentity(): String = checkpointIdentity ?: run {
        if (sqlite != null) return@run sqlite.instance(sqliteId).orEmpty()
        val first = fileLog().pageAfter(-1L, 1).firstOrNull()
        val encoded = first?.let { json.encodeToString(com.labteto.dshmobile.harness.session.SessionEvent.serializer(), it) }.orEmpty()
        MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray())
            .joinToString("") { "%02x".format(it) }.also { checkpointIdentity = it }
    }

    internal fun readProjectionCheckpoint(name: String, version: Int): LocalSessionProjectionCheckpoint? = if (isClosed) null else withWriterLease {
        if (isClosed) null else if (sqlite != null) sqlite.readProjectionCheckpoint(sqliteId, name, version)
        else checkpoints.read(name)?.takeIf {
            it.version == version && it.identity == projectionIdentity() &&
                it.throughSequence in -1L..latestSequence()
        }
    }

    internal fun writeProjectionCheckpoint(name: String, version: Int, throughSequence: Long,
        payload: String, generation: Long) = if (isClosed) Unit else withWriterLease {
        if (isClosed || generation != resetGeneration || throughSequence > latestSequence()) return@withWriterLease
        val checkpoint = LocalSessionProjectionCheckpoint(version, projectionIdentity(), throughSequence, payload)
        if (sqlite != null) sqlite.writeProjectionCheckpoint(sqliteId, name, checkpoint)
        else checkpoints.write(name, checkpoint)
    }

    internal fun writeProjectionCheckpointIfFits(name: String, version: Int, throughSequence: Long,
        payload: String, generation: Long) = if (isClosed) Unit else withWriterLease {
        if (isClosed || generation != resetGeneration) return@withWriterLease
        val value = LocalSessionProjectionCheckpoint(version, projectionIdentity(), throughSequence, payload)
        if (!checkpoints.fits(value)) return@withWriterLease
        try {
            writeProjectionCheckpoint(name, version, throughSequence, payload, generation)
        } catch (failure: java.io.IOException) {
            AppLog.warn("SessionProjection", "派生检查点写入失败，保留原事件日志读取结果 name=$name", failure)
        }
    }

    /** Native writer first, then adapter state; callers already holding the writer may reenter. */
    internal fun <T> withWriterLease(block: () -> T): T =
        if (sqlite != null) sqlite.withWriterLease { synchronized(this) { block() } }
        else fileLog().withWriterLease { synchronized(this) { block() } }

    fun append(type: String, data: JsonObject): Event {
        val committed = (sqlite?.append(sqliteId, type, data)
            ?: fileLog().append(type, data)).toLocalEvent()
        mutableChanges.update { it + 1L }
        return committed
    }

    fun snapshot(): List<Event> = if (sqlite != null) buildList {
        sqlite.forEachAfter(sqliteId, -1L) { add(it.toLocalEvent()) }
    } else fileLog().snapshot().map { it.toLocalEvent() }

    fun latestSequence(): Long = sqlite?.latestSequence(sqliteId) ?: fileLog().latestSequence()

    fun diagnostics() = if (sqlite != null)
        com.labteto.dshmobile.harness.session.SessionEventLogDiagnostics(0L, 0L, 0L)
    else fileLog().diagnostics()

    @Volatile internal var isClosed: Boolean = false
        private set

    fun close() {
        // No writer is acquired while holding this monitor: readers consistently enter
        // native writer -> adapter state. Release adapter state before core registration close.
        val release = synchronized(this) {
            if (isClosed) false else {
                isClosed = true
                resetGeneration += 1L
                true
            }
        }
        if (release) {
            // Core close releases path registration; never hold its writer while taking
            // the core registry lock, whose own close path acquires the writer internally.
            if (sqlite == null) fileLog().close()
            mutableChanges.update { it + 1L }
        }
    }

    fun controlEventsAfter(
        sequenceExclusive: Long, types: Set<String>,
    ): List<Event> {
        require(types.isNotEmpty())
        return (if (sqlite != null) sqlite.selectTypesAfter(sqliteId, sequenceExclusive, types)
        else fileLog().snapshotAfter(sequenceExclusive).filter { it.type in types })
            .map { it.toLocalEvent() }
    }

    fun snapshotAfter(sequenceExclusive: Long): List<Event> = if (sqlite != null) buildList {
        sqlite.forEachAfter(sqliteId, sequenceExclusive) { add(it.toLocalEvent()) }
    } else fileLog().snapshotAfter(sequenceExclusive).map { it.toLocalEvent() }

    /** Streaming projection over committed Session facts. Never copy a whole historical tail. */
    fun forEachAfter(sequenceExclusive: Long, visitor: (Event) -> Unit) {
        if (sqlite != null) sqlite.forEachAfter(sqliteId, sequenceExclusive) { visitor(it.toLocalEvent()) }
        else fileLog().forEachAfter(sequenceExclusive) { visitor(it.toLocalEvent()) }
    }

    /** 按 sequence 递增返回旧事件页，适合 Projection / fold 等从旧到新的消费路径。 */
    fun pageBeforeChronological(
        sequenceExclusive: Long = Long.MAX_VALUE,
        limit: Int = 80,
    ): List<Event> = (if (sqlite != null)
        sqlite.pageBeforeChronological(sqliteId, sequenceExclusive, limit)
        else fileLog().pageBeforeChronological(sequenceExclusive, limit)).map { it.toLocalEvent() }

    /**
     * Transcript-only reverse feed. Native SQLite uses the visible-hint index;
     * file-backed JVM contracts retain the original full event scan.
     */
    internal fun pageVisibleTranscriptBefore(
        sequenceExclusive: Long = Long.MAX_VALUE,
        limit: Int = 80,
    ): List<Event> = (if (sqlite != null)
        sqlite.pageVisibleBeforeNewestFirst(sqliteId, sequenceExclusive, limit)
        else fileLog().pageBeforeNewestFirst(sequenceExclusive, limit)).map { it.toLocalEvent() }

    /** 按 sequence 递减返回旧事件页，适合从最新事件向历史倒查。 */
    fun pageBeforeNewestFirst(
        sequenceExclusive: Long = Long.MAX_VALUE,
        limit: Int = 80,
    ): List<Event> = (if (sqlite != null)
        sqlite.pageBeforeNewestFirst(sqliteId, sequenceExclusive, limit)
        else fileLog().pageBeforeNewestFirst(sequenceExclusive, limit)).map { it.toLocalEvent() }

    fun pageAfter(sequenceExclusive: Long, limit: Int = 80): List<Event> =
        (if (sqlite != null) sqlite.pageAfter(sqliteId, sequenceExclusive, limit)
         else fileLog().pageAfter(sequenceExclusive, limit)).map { it.toLocalEvent() }

    /**
     * Consume a frozen durable stream within [block], without materializing the whole session.
     * The sequence must not escape the block. Early return, failure and full traversal all close
     * every snapshot and decompressor; suspended sequence builders cannot own these resources.
     */
    fun <T> withEvents(block: (Sequence<Event>) -> T): T {
        if (sqlite != null) return sqlite.withEvents(sqliteId) { events ->
            block(events.map { it.toLocalEvent() })
        }
        val snapshots = fileLog().openDurableFileSnapshot()
        var activeReader: java.io.BufferedReader? = null
        var malformedRows = 0
        try {
            return block(sequence {
                for (source in snapshots) {
                    val frozenInput = SnapshotBoundedInputStream(source.input, source.length)
                    val reader = (if (source.name.endsWith(".gz")) GZIPInputStream(frozenInput)
                    else frozenInput).bufferedReader()
                    activeReader = reader
                    while (true) {
                        val line = readBoundedLine(reader, MAX_DURABLE_EVENT_LINE_CHARS) ?: break
                        val event = runCatching {
                            com.labteto.dshmobile.harness.session.SessionEventSchemaCodec
                                .decode(json, line).toLocalEvent()
                        }.onFailure { error ->
                            if (error is com.labteto.dshmobile.harness.session.UnsupportedSessionEventSchemaVersion)
                                throw error
                            malformedRows += 1
                            if (malformedRows <= MAX_DECODE_FAILURE_SAMPLES) {
                                // Event bodies may contain private prompts and credentials: never export parser snippets.
                                AppLog.warn(
                                    "SessionEventLog",
                                    "operation=decode_durable_event session_id=${sessionId.orEmpty().take(80)} " +
                                        "source=${source.name.take(100)} row_failure=$malformedRows " +
                                        "cause_type=${error.javaClass.simpleName}",
                                )
                            }
                        }.getOrNull() ?: continue
                        yield(event)
                    }
                    reader.close()
                    activeReader = null
                }
            })
        } finally {
            if (malformedRows > MAX_DECODE_FAILURE_SAMPLES) {
                AppLog.warn("SessionEventLog", "operation=decode_durable_event " +
                    "session_id=${sessionId.orEmpty().take(80)} malformed_rows=$malformedRows " +
                    "logged_samples=$MAX_DECODE_FAILURE_SAMPLES")
            }
            runCatching { activeReader?.close() }
            snapshots.forEach { runCatching { it.close() } }
        }
    }

    fun repairInterruptedTail(force: Boolean = false): SessionRepairResult =
        if (!force && sessionId?.let(LocalSessionRuntimeRegistry::hasLiveOwner) == true) {
            SessionRepairResult()
        } else {
            if (sqlite == null) SessionRecovery.repairInterruptedTail(fileLog())
            else SessionRecovery.repairInterruptedTail(
                withWriterLease = { task -> sqlite.withWriterLease { task() } },
                latest = { type -> sqlite.latest(sqliteId, type) },
                forEachAfter = { sequence, visit -> sqlite.forEachAfter(sqliteId, sequence, visit) },
                append = { type, data ->
                    val event = sqlite.append(sqliteId, type, data)
                    mutableChanges.update { it + 1L }
                    event
                },
            )
        }

    fun search(
        query: String,
        limit: Int = 50,
        afterSequence: Long = -1L,
    ): String = if (sqlite != null) sqlite.search(sqliteId, query, limit, afterSequence)
        else fileLog().search(query, limit, afterSequence)

    fun tail(limit: Int = 40): String = if (sqlite != null) sqlite.tail(sqliteId, limit)
        else fileLog().tail(limit)

    fun read(sequence: Long, before: Int = 0, after: Int = 0, offsetChars: Int = 0): String =
        if (sqlite != null) sqlite.read(sqliteId, sequence, before, after, offsetChars)
        else fileLog().read(sequence, before, after, offsetChars)

    fun latestMatching(
        types: Set<String>,
        beforeSequenceExclusive: Long = Long.MAX_VALUE,
        predicate: (JsonObject) -> Boolean,
    ): Event? = (if (sqlite != null)
        sqlite.latestMatching(sqliteId, types, beforeSequenceExclusive, predicate)
        else fileLog().latestMatching(types, beforeSequenceExclusive, predicate))?.toLocalEvent()

    /** Android-facing batched latest-by-identity projection over the single durable core log. */
    fun <K> latestMatchingByKey(
        types: Set<String>,
        requiredKeys: Set<K>,
        keyOf: (Event) -> K?,
    ): Map<K, Event> = (if (sqlite != null)
        sqlite.latestMatchingByKey(sqliteId, types, requiredKeys) { keyOf(it.toLocalEvent()) }
        else fileLog().latestMatchingByKey(types, requiredKeys) { keyOf(it.toLocalEvent()) }
    ).mapValues { (_, event) -> event.toLocalEvent() }

    fun latest(
        type: String,
        beforeSequenceExclusive: Long = Long.MAX_VALUE,
    ): Event? = (if (sqlite != null) sqlite.latest(sqliteId, type, beforeSequenceExclusive)
        else fileLog().latest(type, beforeSequenceExclusive))?.toLocalEvent()

    fun latestOf(types: Set<String>): Event? =
        (if (sqlite != null) sqlite.latestMatching(sqliteId, types) { true }
        else fileLog().latestOf(types))?.toLocalEvent()

    @Volatile internal var resetGeneration: Long = 0L
        private set

    fun clear() = withWriterLease {
        if (sqlite != null) sqlite.clearSession(sqliteId) else fileLog().clear()
        resetGeneration += 1L
        checkpointIdentity = null
        if (sqlite == null) checkpoints.clear()
        mutableChanges.update { it + 1L }
    }

    private fun com.labteto.dshmobile.harness.session.SessionEvent.toLocalEvent() = Event(
        sequence = sequence,
        type = type,
        createdAt = createdAt,
        data = data,
    )

    private class SnapshotBoundedInputStream(
        input: InputStream,
        length: Long,
    ) : FilterInputStream(input) {
        private var remaining = length.coerceAtLeast(0L)

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = super.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val allowed = minOf(length.toLong(), remaining).toInt()
            val count = super.read(buffer, offset, allowed)
            if (count > 0) remaining -= count
            return count
        }

        override fun skip(count: Long): Long {
            if (remaining <= 0L) return 0L
            val skipped = super.skip(minOf(count, remaining))
            remaining -= skipped
            return skipped
        }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 8L * 1024L * 1024L
        private const val MAX_DURABLE_EVENT_LINE_CHARS = 16 * 1024 * 1024
        private const val MAX_DECODE_FAILURE_SAMPLES = 3
    }
}
