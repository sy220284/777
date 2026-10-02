package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.SessionEventLog
import com.labteto.dshmobile.harness.session.SessionRepairResult
import com.labteto.dshmobile.harness.session.SessionRecovery
import com.labteto.dshmobile.observability.AppLog
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Android-facing adapter kept source-compatible while storage lives in the native core module. */
class LocalSessionEventLog(
    private val file: File,
    private val json: Json,
    maxBytes: Long = DEFAULT_MAX_BYTES,
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

    /** One chronological page strictly older than [sequenceExclusive], read newest-first on disk. */
    fun pageBefore(
        sequenceExclusive: Long = Long.MAX_VALUE,
        limit: Int = 80,
    ): List<Event> = delegate.pageBefore(sequenceExclusive, limit).map { event ->
        event.toLocalEvent()
    }

    fun pageAfter(sequenceExclusive: Long, limit: Int = 80): List<Event> =
        delegate.pageAfter(sequenceExclusive, limit).map { it.toLocalEvent() }

    /**
     * Stream durable events in sequence order without materializing the whole session in memory.
     * UI projections such as conversation files only keep their compact projection state.
     */
    fun events(): Sequence<Event> = sequence {
        for (source in orderedFiles()) {
            (if (source.name.endsWith(".gz")) GZIPInputStream(source.inputStream().buffered()).bufferedReader()
            else source.bufferedReader()).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val event = runCatching {
                        json.decodeFromString(Event.serializer(), line)
                    }.getOrNull() ?: continue
                    yield(event)
                }
            }
        }
    }

    fun repairInterruptedTail(): SessionRepairResult =
        SessionRecovery.repairInterruptedTail(delegate)

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

    fun clear() = delegate.clear()

    private fun com.labteto.dshmobile.harness.session.SessionEvent.toLocalEvent() = Event(
        sequence = sequence,
        type = type,
        createdAt = createdAt,
        data = data,
    )

    private fun orderedFiles(): List<File> {
        val parent = file.parentFile ?: return listOfNotNull(file.takeIf(File::isFile))
        val prefix = "${file.name}.part-"
        val segments = parent.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(prefix) &&
                Regex("[0-9]+(?:\\.gz)?").matches(it.name.removePrefix(prefix)) }
            .mapNotNull { candidate ->
                candidate.name.removePrefix(prefix).removeSuffix(".gz").toIntOrNull()
                    ?.let { it to candidate }
            }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .values.map { copies -> copies.firstOrNull { !it.name.endsWith(".gz") } ?: copies.first() }
        return segments + listOfNotNull(file.takeIf(File::isFile))
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 8L * 1024L * 1024L
    }
}
