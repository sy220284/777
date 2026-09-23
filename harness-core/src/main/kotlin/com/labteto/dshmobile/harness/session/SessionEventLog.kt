package com.labteto.dshmobile.harness.session

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

/**
 * Append-only source of truth for model-visible session facts.
 *
 * [maxBytes] is a segment bound, not a retention bound. Once the active JSONL file reaches the
 * bound it is moved to an immutable numbered segment and a fresh active file is opened. Historical
 * rows are never discarded merely to make room for newer rows.
 */
class SessionEventLog(
    private val file: File,
    private val json: Json,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxBytes >= MIN_MAX_BYTES) { "事件日志分段上限至少为 $MIN_MAX_BYTES 字节" }
    }

    private val lock = Any()
    private val nextSequence = AtomicLong(readNextSequence())

    fun append(type: String, data: JsonObject): SessionEvent = synchronized(lock) {
        require(type.isNotBlank()) { "事件类型不能为空" }
        val event = SessionEvent(
            sequence = nextSequence.get(),
            type = type,
            createdAt = clock(),
            data = data,
        )
        val encoded = json.encodeToString(SessionEvent.serializer(), event) + "\n"
        val incomingBytes = encoded.toByteArray().size.toLong()
        require(incomingBytes <= maxBytes) { "单条会话事件超过日志分段上限" }
        file.parentFile?.mkdirs()
        if (file.isFile && file.length() + incomingBytes > maxBytes) rotateActiveSegment()
        file.appendText(encoded)
        nextSequence.incrementAndGet()
        event
    }

    fun snapshot(): List<SessionEvent> = synchronized(lock) { readEventsUnsafe() }

    /** Latest durable event sequence, or -1 when the log is empty. */
    fun latestSequence(): Long = synchronized(lock) { nextSequence.get() - 1L }

    /**
     * Return events strictly newer than a persisted projection cursor.
     *
     * Callers can persist a materialized projection together with [latestSequence] and only fold
     * the durable tail after restart. This is the bridge toward SessionEventLog becoming the single
     * source of truth while snapshots remain disposable acceleration checkpoints.
     */
    fun snapshotAfter(sequenceExclusive: Long): List<SessionEvent> = synchronized(lock) {
        buildList {
            forEachAfterUnsafe(sequenceExclusive, ::add)
        }
    }

    /**
     * Visit durable events newer than [sequenceExclusive] without materializing the tail.
     *
     * Startup recovery uses this path so a long-lived session cannot temporarily duplicate its
     * entire event archive in the Android heap.
     */
    fun forEachAfter(
        sequenceExclusive: Long,
        visitor: (SessionEvent) -> Unit,
    ) = synchronized(lock) {
        forEachAfterUnsafe(sequenceExclusive, visitor)
    }

    fun search(query: String, limit: Int = 50): String {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        val wanted = limit.coerceIn(1, MAX_READ_LINES)
        val matches = synchronized(lock) {
            val result = mutableListOf<String>()
            var row = 0
            for (source in orderedFilesUnsafe()) {
                source.forEachLine { line ->
                    row += 1
                    if (result.size < wanted && line.contains(query, ignoreCase = true)) {
                        result += "$row: $line"
                    }
                }
                if (result.size >= wanted) break
            }
            result
        }
        return if (matches.isEmpty()) "未找到会话事件" else matches.joinToString("\n")
    }

    fun tail(limit: Int = 40): String {
        val wanted = limit.coerceIn(1, MAX_READ_LINES)
        val lines = synchronized(lock) {
            val retained = ArrayDeque<String>(wanted)
            for (source in orderedFilesUnsafe()) {
                source.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    if (retained.size >= wanted) retained.removeFirst()
                    retained.addLast(line)
                }
            }
            retained.toList()
        }
        return if (lines.isEmpty()) "会话事件日志为空" else lines.joinToString("\n")
    }

    fun read(sequence: Long, before: Int = 0, after: Int = 0): String {
        val events = snapshot()
        if (events.isEmpty()) return "会话事件日志为空"
        val target = events.indexOfFirst { it.sequence == sequence }
        if (target < 0) return "事件不存在：$sequence"
        val from = (target - before.coerceIn(0, MAX_CONTEXT_LINES)).coerceAtLeast(0)
        val to = (target + after.coerceIn(0, MAX_CONTEXT_LINES) + 1).coerceAtMost(events.size)
        return events.subList(from, to)
            .joinToString("\n") { json.encodeToString(SessionEvent.serializer(), it) }
    }

    fun latest(
        type: String,
        beforeSequenceExclusive: Long = Long.MAX_VALUE,
    ): SessionEvent? = synchronized(lock) {
        require(type.isNotBlank()) { "事件类型不能为空" }
        for (source in orderedFilesUnsafe().asReversed()) {
            var found: SessionEvent? = null
            source.forEachLine { line ->
                val event = decodeEventOrNull(line) ?: return@forEachLine
                if (
                    event.sequence < beforeSequenceExclusive &&
                    event.type == type &&
                    (found == null || event.sequence > requireNotNull(found).sequence)
                ) {
                    found = event
                }
            }
            found?.let { return@synchronized it }
        }
        null
    }

    fun clear() {
        synchronized(lock) {
            segmentFilesUnsafe().forEach { it.delete() }
            file.parentFile?.mkdirs()
            file.writeText("")
            nextSequence.set(0L)
        }
    }

    /**
     * Recover the next sequence from the newest valid row only.
     *
     * Each segment is bounded by [maxBytes] and sequences are monotonic across rotations, so
     * replaying every historical JSON row at startup is unnecessary. Scanning newest files
     * backwards keeps restart cost bounded by one segment in the normal case while still
     * tolerating a torn/corrupt tail.
     */
    private fun readNextSequence(): Long {
        for (source in orderedFilesUnsafe().asReversed()) {
            val latest = readLastValidEventUnsafe(source) ?: continue
            return latest.sequence + 1L
        }
        return 0L
    }

    private fun readLastValidEventUnsafe(source: File): SessionEvent? {
        var latest: SessionEvent? = null
        if (!source.isFile) return null
        source.forEachLine { line ->
            decodeEventOrNull(line)?.let { event ->
                if (latest == null || event.sequence > requireNotNull(latest).sequence) {
                    latest = event
                }
            }
        }
        return latest
    }

    private inline fun forEachAfterUnsafe(
        sequenceExclusive: Long,
        visitor: (SessionEvent) -> Unit,
    ) {
        val sources = orderedFilesUnsafe()
        if (sources.isEmpty()) return

        // Find only the segments that can contain the requested tail. Segment inspection is
        // streaming, so even an 8 MiB segment does not require one equally large ByteArray.
        val relevant = ArrayDeque<File>()
        for (source in sources.asReversed()) {
            val last = readLastValidEventUnsafe(source) ?: continue
            if (last.sequence <= sequenceExclusive) break
            relevant.addFirst(source)
        }

        relevant.forEach { source ->
            source.forEachLine { line ->
                val event = decodeEventOrNull(line) ?: return@forEachLine
                if (event.sequence > sequenceExclusive) visitor(event)
            }
        }
    }

    private fun decodeEventOrNull(line: String): SessionEvent? =
        runCatching { json.decodeFromString(SessionEvent.serializer(), line) }.getOrNull()

    private fun readEventsUnsafe(): List<SessionEvent> {
        val events = mutableListOf<SessionEvent>()
        for (source in orderedFilesUnsafe()) {
            source.forEachLine { line ->
                runCatching { json.decodeFromString(SessionEvent.serializer(), line) }
                    .getOrNull()
                    ?.let(events::add)
            }
        }
        return events
    }

    private fun orderedFilesUnsafe(): List<File> =
        segmentFilesUnsafe() + listOfNotNull(file.takeIf { it.isFile })

    private fun segmentFilesUnsafe(): List<File> {
        val parent = file.parentFile ?: return emptyList()
        val prefix = "${file.name}.part-"
        return parent.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(prefix) }
            .sortedBy { it.name.removePrefix(prefix).toIntOrNull() ?: Int.MAX_VALUE }
    }

    private fun rotateActiveSegment() {
        if (!file.isFile || file.length() == 0L) return
        val parent = file.parentFile ?: error("事件日志缺少父目录")
        parent.mkdirs()
        val prefix = "${file.name}.part-"
        val nextIndex = segmentFilesUnsafe()
            .mapNotNull { it.name.removePrefix(prefix).toIntOrNull() }
            .maxOrNull()
            ?.plus(1)
            ?: 1
        val target = File(parent, "$prefix${nextIndex.toString().padStart(6, '0')}")
        try {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(file.toPath(), target.toPath())
        }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 8L * 1024L * 1024L
        const val MIN_MAX_BYTES = 512L
        const val MAX_READ_LINES = 200
        const val MAX_CONTEXT_LINES = 20
    }
}
