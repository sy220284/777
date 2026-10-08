package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.io.readBoundedLine

import com.labteto.dshmobile.harness.session.SessionEventLog
import com.labteto.dshmobile.harness.session.SessionRecovery
import com.labteto.dshmobile.harness.session.SessionRepairResult
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.observability.AppLog
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Android-facing event adapter; durable storage lives in the native core module. */
class LocalSessionEventLog(
    private val file: File,
    private val json: Json,
    maxBytes: Long = DEFAULT_MAX_BYTES,
    private val sessionId: String? = null,
) {
    @Serializable
    data class Event(
        val sequence: Long,
        val type: String,
        val createdAt: Long,
        val data: JsonObject,
    )

    private val delegate = SessionEventLog(
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
    )

    fun append(type: String, data: JsonObject): Event =
        delegate.append(type, data).toLocalEvent()

    fun snapshot(): List<Event> = delegate.snapshot().map { event ->
        event.toLocalEvent()
    }

    fun latestSequence(): Long = delegate.latestSequence()

    fun diagnostics() = delegate.diagnostics()

    fun close() = delegate.close()

    fun snapshotAfter(sequenceExclusive: Long): List<Event> = delegate.snapshotAfter(sequenceExclusive).map { event ->
        event.toLocalEvent()
    }

    @Deprecated(
        message = "方向语义不明确；请显式使用 pageBeforeChronological 或 pageBeforeNewestFirst",
        replaceWith = ReplaceWith("pageBeforeChronological(sequenceExclusive, limit)"),
    )
    fun pageBefore(
        sequenceExclusive: Long = Long.MAX_VALUE,
        limit: Int = 80,
    ): List<Event> = pageBeforeChronological(sequenceExclusive, limit)

    /** 按 sequence 递增返回旧事件页，适合 Projection / fold 等从旧到新的消费路径。 */
    fun pageBeforeChronological(
        sequenceExclusive: Long = Long.MAX_VALUE,
        limit: Int = 80,
    ): List<Event> = delegate.pageBeforeChronological(sequenceExclusive, limit).map { event ->
        event.toLocalEvent()
    }

    /** 按 sequence 递减返回旧事件页，适合从最新事件向历史倒查。 */
    fun pageBeforeNewestFirst(
        sequenceExclusive: Long = Long.MAX_VALUE,
        limit: Int = 80,
    ): List<Event> = delegate.pageBeforeNewestFirst(sequenceExclusive, limit).map { event ->
        event.toLocalEvent()
    }

    fun pageAfter(sequenceExclusive: Long, limit: Int = 80): List<Event> =
        delegate.pageAfter(sequenceExclusive, limit).map { it.toLocalEvent() }

    /**
     * Consume a frozen durable stream within [block], without materializing the whole session.
     * The sequence must not escape the block. Early return, failure and full traversal all close
     * every snapshot and decompressor; suspended sequence builders cannot own these resources.
     */
    fun <T> withEvents(block: (Sequence<Event>) -> T): T {
        val snapshots = delegate.openDurableFileSnapshot()
        var activeReader: java.io.BufferedReader? = null
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
                            json.decodeFromString(Event.serializer(), line)
                        }.getOrNull() ?: continue
                        yield(event)
                    }
                    reader.close()
                    activeReader = null
                }
            })
        } finally {
            runCatching { activeReader?.close() }
            snapshots.forEach { runCatching { it.close() } }
        }
    }

    fun repairInterruptedTail(force: Boolean = false): SessionRepairResult =
        if (!force && sessionId?.let(LocalSessionRuntimeRegistry::hasLiveOwner) == true) {
            SessionRepairResult()
        } else {
            SessionRecovery.repairInterruptedTail(delegate)
        }

    fun search(
        query: String,
        limit: Int = 50,
        afterSequence: Long = -1L,
    ): String = delegate.search(query, limit, afterSequence)

    fun tail(limit: Int = 40): String = delegate.tail(limit)

    fun read(sequence: Long, before: Int = 0, after: Int = 0, offsetChars: Int = 0): String =
        delegate.read(sequence, before, after, offsetChars)

    fun latestMatching(
        types: Set<String>,
        beforeSequenceExclusive: Long = Long.MAX_VALUE,
        predicate: (JsonObject) -> Boolean,
    ): Event? = delegate.latestMatching(types, beforeSequenceExclusive, predicate)?.toLocalEvent()

    fun latest(
        type: String,
        beforeSequenceExclusive: Long = Long.MAX_VALUE,
    ): Event? = delegate.latest(type, beforeSequenceExclusive)?.toLocalEvent()

    fun latestOf(types: Set<String>): Event? =
        delegate.latestOf(types)?.toLocalEvent()

    @Volatile internal var resetGeneration: Long = 0L
        private set

    fun clear() = synchronized(this) {
        delegate.clear()
        resetGeneration += 1L
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
    }
}
