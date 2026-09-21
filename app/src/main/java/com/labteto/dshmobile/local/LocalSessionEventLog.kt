package com.labteto.dshmobile.local

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Append-only audit log for every fact that becomes visible to the model. */
class LocalSessionEventLog(
    private val file: File,
    private val json: Json,
) {
    @Serializable
    data class Event(
        val sequence: Long,
        val type: String,
        val createdAt: Long,
        val data: JsonObject,
    )

    private val lock = Any()
    private val nextSequence = AtomicLong(
        runCatching { if (file.isFile) file.useLines { it.count().toLong() } else 0L }.getOrDefault(0L),
    )

    fun append(type: String, data: JsonObject) {
        val event = Event(
            sequence = nextSequence.getAndIncrement(),
            type = type,
            createdAt = System.currentTimeMillis(),
            data = data,
        )
        val encoded = json.encodeToString(Event.serializer(), event) + "\n"
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.appendText(encoded)
        }
    }

    fun search(query: String, limit: Int = 50): String {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        if (!file.isFile) return "会话事件日志为空"
        val matches = file.useLines { lines ->
            lines.mapIndexedNotNull { index, line ->
                line.takeIf { it.contains(query, ignoreCase = true) }?.let { "${index + 1}: $it" }
            }.take(limit.coerceIn(1, 200)).toList()
        }
        return if (matches.isEmpty()) "未找到会话事件" else matches.joinToString("\n")
    }

    fun tail(limit: Int = 40): String {
        if (!file.isFile) return "会话事件日志为空"
        val lines = file.readLines()
        return lines.takeLast(limit.coerceIn(1, 200)).joinToString("\n")
    }

    fun read(sequence: Long, before: Int = 0, after: Int = 0): String {
        if (!file.isFile) return "会话事件日志为空"
        val lines = file.readLines()
        val target = lines.indexOfFirst { line ->
            runCatching { json.decodeFromString(Event.serializer(), line).sequence == sequence }.getOrDefault(false)
        }
        if (target < 0) return "事件不存在：$sequence"
        val from = (target - before.coerceIn(0, 20)).coerceAtLeast(0)
        val to = (target + after.coerceIn(0, 20) + 1).coerceAtMost(lines.size)
        return lines.subList(from, to).joinToString("\n")
    }

    fun clear() {
        synchronized(lock) {
            file.writeText("")
            nextSequence.set(0L)
        }
    }
}
