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
    file: File,
    json: Json,
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

    fun append(type: String, data: JsonObject) {
        delegate.append(type, data)
    }

    fun snapshot(): List<Event> = delegate.snapshot().map { event ->
        Event(event.sequence, event.type, event.createdAt, event.data)
    }

    fun repairInterruptedTail(): SessionRepairResult =
        SessionRecovery.repairInterruptedTail(delegate)

    fun search(query: String, limit: Int = 50): String = delegate.search(query, limit)

    fun tail(limit: Int = 40): String = delegate.tail(limit)

    fun read(sequence: Long, before: Int = 0, after: Int = 0): String =
        delegate.read(sequence, before, after)

    fun latest(type: String): Event? = delegate.latest(type)?.let { event ->
        Event(
            sequence = event.sequence,
            type = event.type,
            createdAt = event.createdAt,
            data = event.data,
        )
    }

    fun clear() = delegate.clear()

    private companion object {
        const val DEFAULT_MAX_BYTES = 8L * 1024L * 1024L
    }
}
