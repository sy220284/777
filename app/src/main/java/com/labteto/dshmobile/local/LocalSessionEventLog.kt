package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.SessionEventLog
import com.labteto.dshmobile.harness.session.SessionRepairResult
import com.labteto.dshmobile.harness.session.SessionRecovery
import java.io.File
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

    private val delegate = SessionEventLog(file, json, maxBytes)

    fun append(type: String, data: JsonObject): Event =
        delegate.append(type, data).toLocalEvent()

    fun snapshot(): List<Event> = delegate.snapshot().map { event ->
        event.toLocalEvent()
    }

    fun latestSequence(): Long = delegate.latestSequence()

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

    /**
     * Stream durable events in sequence order without materializing the whole session in memory.
     * UI projections such as conversation files only keep their compact projection state.
     */
    fun events(): Sequence<Event> = sequence {
        for (source in orderedFiles()) {
            source.bufferedReader().use { reader ->
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

    fun search(query: String, limit: Int = 50): String = delegate.search(query, limit)

    fun tail(limit: Int = 40): String = delegate.tail(limit)

    fun read(sequence: Long, before: Int = 0, after: Int = 0): String =
        delegate.read(sequence, before, after)

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
            .filter { it.isFile && it.name.startsWith(prefix) }
            .sortedBy { it.name.removePrefix(prefix).toIntOrNull() ?: Int.MAX_VALUE }
        return segments + listOfNotNull(file.takeIf(File::isFile))
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 8L * 1024L * 1024L
    }
}
