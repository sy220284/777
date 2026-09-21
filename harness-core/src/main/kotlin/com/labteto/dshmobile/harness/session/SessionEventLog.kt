package com.labteto.dshmobile.harness.session

import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class SessionEvent(
    val sequence: Long,
    val type: String,
    val createdAt: Long,
    val data: JsonObject,
)

/** Append-only source of truth for model-visible session facts. */
class SessionEventLog(
    private val file: File,
    private val json: Json,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxBytes >= MIN_MAX_BYTES) { "事件日志上限至少为 $MIN_MAX_BYTES 字节" }
    }

    private val lock = Any()
    private val nextSequence = AtomicLong(readNextSequence())

    fun append(type: String, data: JsonObject): SessionEvent = synchronized(lock) {
        require(type.isNotBlank()) { "事件类型不能为空" }
        val event = SessionEvent(
            sequence = nextSequence.getAndIncrement(),
            type = type,
            createdAt = clock(),
            data = data,
        )
        val encoded = json.encodeToString(SessionEvent.serializer(), event) + "\n"
        val incomingBytes = encoded.toByteArray().size.toLong()
        require(incomingBytes <= maxBytes) { "单条会话事件超过日志上限" }
        file.parentFile?.mkdirs()
        if (file.length() + incomingBytes > maxBytes) trimToFit(incomingBytes)
        file.appendText(encoded)
        event
    }

    fun search(query: String, limit: Int = 50): String {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        val matches = synchronized(lock) {
            if (!file.isFile) return "会话事件日志为空"
            file.useLines { lines ->
                lines.mapIndexedNotNull { index, line ->
                    line.takeIf { it.contains(query, ignoreCase = true) }?.let { "${index + 1}: $it" }
                }.take(limit.coerceIn(1, MAX_READ_LINES)).toList()
            }
        }
        return if (matches.isEmpty()) "未找到会话事件" else matches.joinToString("\n")
    }

    fun tail(limit: Int = 40): String {
        val lines = synchronized(lock) {
            if (!file.isFile) return "会话事件日志为空"
            file.readLines()
        }
        return lines.takeLast(limit.coerceIn(1, MAX_READ_LINES)).joinToString("\n")
    }

    fun read(sequence: Long, before: Int = 0, after: Int = 0): String {
        val lines = synchronized(lock) {
            if (!file.isFile) return "会话事件日志为空"
            file.readLines()
        }
        val target = lines.indexOfFirst { line ->
            runCatching { json.decodeFromString(SessionEvent.serializer(), line).sequence == sequence }
                .getOrDefault(false)
        }
        if (target < 0) return "事件不存在：$sequence"
        val from = (target - before.coerceIn(0, MAX_CONTEXT_LINES)).coerceAtLeast(0)
        val to = (target + after.coerceIn(0, MAX_CONTEXT_LINES) + 1).coerceAtMost(lines.size)
        return lines.subList(from, to).joinToString("\n")
    }

    fun clear() {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.writeText("")
            nextSequence.set(0L)
        }
    }

    private fun readNextSequence(): Long = runCatching {
        if (!file.isFile) 0L else file.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching { json.decodeFromString(SessionEvent.serializer(), line).sequence }.getOrNull()
            }.maxOrNull()?.plus(1) ?: 0L
        }
    }.getOrDefault(0L)

    /** Retains only complete newest JSONL rows and reserves space for the incoming event. */
    private fun trimToFit(incomingBytes: Long) {
        if (!file.isFile) return
        val budget = (maxBytes - incomingBytes).coerceAtLeast(0L)
        val retained = ArrayDeque<Pair<String, Int>>()
        var retainedBytes = 0L
        file.forEachLine { line ->
            val bytes = line.toByteArray().size + 1
            retained.addLast(line to bytes)
            retainedBytes += bytes
            while (retainedBytes > budget && retained.isNotEmpty()) {
                retainedBytes -= retained.removeFirst().second
            }
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.bufferedWriter().use { writer ->
            retained.forEach { (line, _) ->
                writer.write(line)
                writer.newLine()
            }
        }
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 8L * 1024L * 1024L
        const val MIN_MAX_BYTES = 512L
        const val MAX_READ_LINES = 200
        const val MAX_CONTEXT_LINES = 20
    }
}
