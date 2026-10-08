package com.labteto.dshmobile.harness.session

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
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

data class SessionEventFileSnapshot(
    val name: String,
    val lastModified: Long,
    val length: Long,
    val input: InputStream,
) : AutoCloseable {
    override fun close() = input.close()
}

data class SessionEventLogDiagnostics(
    val malformedRows: Long,
    val segmentReadFailures: Long,
    val archiveFailures: Long,
)

/**
 * Append-only source of truth for model-visible session facts.
 *
 * [maxBytes] is a segment bound, not a retention bound. Once the active JSONL file reaches the
 * bound it is moved to a compressed immutable numbered segment and a fresh active file is opened.
 * Historical rows are never discarded merely to make room for newer rows.
 */
class SessionEventLog(
    private val file: File,
    private val json: Json,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxEventBytes: Long = maxOf(maxBytes, DEFAULT_MAX_EVENT_BYTES),
    private val clock: () -> Long = System::currentTimeMillis,
    private val diagnosticSink: (String, Throwable?) -> Unit = { _, _ -> },
    private val onSegmentRotated: () -> Unit = {},
) : AutoCloseable {
    init {
        require(maxBytes >= MIN_MAX_BYTES) { "事件日志分段上限至少为 $MIN_MAX_BYTES 字节" }
        require(maxEventBytes >= maxBytes) { "单条事件上限不能小于日志分段目标" }
    }

    // All live adapters for the same durable path share one lock and one sequence cursor.
    // The first adapter after process start reads the durable tail once; later adapters reuse the
    // same path state instead of re-enumerating segments on every append.
    private val pathKey = file.canonicalFile.path
    private val pathState = requireNotNull(
        PATH_STATES.compute(pathKey) { _, existing ->
            val state = existing ?: SharedPathState()
            synchronized(state.lock) { state.references += 1 }
            state
        },
    )
    private val closed = AtomicBoolean(false)
    private val lock = pathState.lock
    private val malformedRows = AtomicLong()
    private val segmentReadFailures = AtomicLong()
    private val archiveFailures = AtomicLong()
    private val nextSequence = pathState.nextSequence

    init {
        synchronized(lock) {
            if (nextSequence.get() == UNINITIALIZED_SEQUENCE) {
                repairTornActiveTailUnsafe()
                nextSequence.set(readNextSequence())
            }
        }
    }

    fun diagnostics(): SessionEventLogDiagnostics = SessionEventLogDiagnostics(
        malformedRows = malformedRows.get(),
        segmentReadFailures = segmentReadFailures.get(),
        archiveFailures = archiveFailures.get(),
    )

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
        require(incomingBytes <= maxEventBytes) { "单条会话事件超过事件硬上限" }
        file.parentFile?.mkdirs()
        if (file.isFile && file.length() > 0L && file.length() + incomingBytes > maxBytes) {
            rotateActiveSegment()
        }
        try {
            file.appendText(encoded)
        } catch (error: Exception) {
            runCatching { repairTornActiveTailUnsafe() }
            throw error
        }
        nextSequence.incrementAndGet()
        event
    }

    fun snapshot(): List<SessionEvent> = synchronized(lock) { readEventsUnsafe() }

    /** Latest durable event sequence, or -1 when the log is empty. */
    fun latestSequence(): Long = synchronized(lock) {
        nextSequence.get().coerceAtLeast(0L) - 1L
    }

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
     * Return one chronological page strictly older than [sequenceExclusive].
     *
     * Returned sequence values are strictly increasing. Use this for projection/fold inputs and
     * any caller that consumes events from old to new.
     */
    fun pageBeforeChronological(
        sequenceExclusive: Long = Long.MAX_VALUE,
        limit: Int = DEFAULT_PAGE_EVENTS,
    ): List<SessionEvent> = synchronized(lock) {
        pageBeforeNewestFirstUnsafe(sequenceExclusive, limit).asReversed()
    }

    /**
     * Return one newest-first page strictly older than [sequenceExclusive].
     *
     * Returned sequence values are strictly decreasing. Use this for backward lookup/recovery so
     * callers never need to reverse a chronological page by hand.
     */
    fun pageBeforeNewestFirst(
        sequenceExclusive: Long = Long.MAX_VALUE,
        limit: Int = DEFAULT_PAGE_EVENTS,
    ): List<SessionEvent> = synchronized(lock) {
        pageBeforeNewestFirstUnsafe(sequenceExclusive, limit)
    }

    private fun pageBeforeNewestFirstUnsafe(
        sequenceExclusive: Long,
        limit: Int,
    ): List<SessionEvent> {
        val wanted = limit.coerceIn(1, MAX_PAGE_EVENTS)
        val newestFirst = ArrayList<SessionEvent>(wanted)
        for (source in orderedFilesUnsafe().asReversed()) {
            val first = readFirstValidEventUnsafe(source) ?: continue
            if (first.sequence >= sequenceExclusive) continue
            val completed = forEachEventReverseUnsafe(source) { event ->
                if (event.sequence < sequenceExclusive) newestFirst += event
                newestFirst.size < wanted
            }
            if (!completed || newestFirst.size >= wanted) break
        }
        return newestFirst
    }

    /** One bounded chronological page newer than the cursor; old segments are skipped. */
    fun pageAfter(sequenceExclusive: Long, limit: Int = DEFAULT_PAGE_EVENTS): List<SessionEvent> = synchronized(lock) {
        val wanted = limit.coerceIn(1, MAX_PAGE_EVENTS)
        val result = ArrayList<SessionEvent>(wanted)
        val sources = orderedFilesUnsafe()
        val relevant = ArrayDeque<File>()
        for (source in sources.asReversed()) {
            val last = readLastValidEventUnsafe(source) ?: continue
            if (last.sequence <= sequenceExclusive) break
            relevant.addFirst(source)
        }
        for (source in relevant) {
            try {
                source.eventReader().use { reader ->
                    while (result.size < wanted) {
                        val line = reader.readLine() ?: break
                        val event = decodeEventOrNull(line) ?: continue
                        if (event.sequence > sequenceExclusive) result += event
                    }
                }
            } catch (error: Exception) {
                reportSegmentReadFailure(source, error)
            }
            if (result.size >= wanted) break
        }
        result
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

    fun search(
        query: String,
        limit: Int = 50,
        afterSequence: Long = -1L,
        maxChars: Int = DEFAULT_SEARCH_RESULT_CHARS,
    ): String {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        val wanted = limit.coerceIn(1, MAX_SEARCH_RESULTS)
        val budget = maxChars.coerceIn(MIN_SEARCH_RESULT_CHARS, MAX_SEARCH_RESULT_CHARS)
        val page = synchronized(lock) {
            val result = mutableListOf<String>()
            var usedChars = 0
            var lastSequence: Long? = null
            var paginated = false

            scan@ for (source in orderedFilesUnsafe()) {
                source.forEachEventLine { line ->
                    if (paginated) return@forEachEventLine
                    val event = decodeEventOrNull(line) ?: return@forEachEventLine
                    if (event.sequence <= afterSequence || !line.contains(query, ignoreCase = true)) {
                        return@forEachEventLine
                    }
                    if (result.size >= wanted) {
                        paginated = true
                        return@forEachEventLine
                    }

                    val previewSuffix = if (line.length > MAX_SEARCH_EVENT_CHARS) {
                        "… [事件内容已截断；用 session_event_read(seq=${event.sequence}) 读取完整事件]"
                    } else {
                        ""
                    }
                    val preview = if (previewSuffix.isEmpty()) line else {
                        line.take((MAX_SEARCH_EVENT_CHARS - previewSuffix.length).coerceAtLeast(0)) + previewSuffix
                    }
                    val row = "${event.sequence}: $preview"
                    val separatorChars = if (result.isEmpty()) 0 else 1
                    if (usedChars + separatorChars + row.length > budget) {
                        if (result.isEmpty()) {
                            val hint = "[事件过长；用 session_event_read(seq=${event.sequence}) 读取完整事件]\n"
                            val prefix = "${event.sequence}: $hint"
                            val allowed = (budget - prefix.length).coerceAtLeast(0)
                            result += prefix + line.take(allowed)
                            lastSequence = event.sequence
                        }
                        paginated = true
                        return@forEachEventLine
                    }

                    result += row
                    usedChars += separatorChars + row.length
                    lastSequence = event.sequence
                }
                if (paginated) break@scan
            }
            Triple(result, lastSequence, paginated)
        }

        val matches = page.first
        if (matches.isEmpty()) return "未找到会话事件"
        val body = matches.joinToString("\n")
        if (!page.third) return body

        val cursor = page.second ?: afterSequence
        val footer = "\n[结果已分页；继续调用 session_event_search，并传 after_sequence=$cursor]"
        return body.take((budget - footer.length).coerceAtLeast(0)) + footer
    }

    fun tail(limit: Int = 40): String {
        val wanted = limit.coerceIn(1, MAX_READ_LINES)
        val lines = synchronized(lock) {
            val chunks = ArrayDeque<List<String>>()
            var retainedCount = 0
            for (source in orderedFilesUnsafe().asReversed()) {
                val segmentTail = ArrayDeque<String>(wanted)
                source.forEachEventLine { line ->
                    if (line.isBlank()) return@forEachEventLine
                    if (segmentTail.size >= wanted) segmentTail.removeFirst()
                    segmentTail.addLast(if (line.length > MAX_SEARCH_EVENT_CHARS) {
                        line.take(MAX_SEARCH_EVENT_CHARS) + "… [事件内容已截断；可用 session_event_read 按字符分页读取]"
                    } else line)
                }
                if (segmentTail.isNotEmpty()) {
                    val chunk = segmentTail.toList()
                    chunks.addFirst(chunk)
                    retainedCount += chunk.size
                }
                if (retainedCount >= wanted) break
            }
            chunks.flatMap { it }.takeLast(wanted)
        }
        if (lines.isEmpty()) return "会话事件日志为空"
        val selected = ArrayDeque<String>()
        var used = 0
        for (line in lines.asReversed()) {
            val added = line.length + if (selected.isEmpty()) 0 else 1
            if (used + added > MAX_SEARCH_RESULT_CHARS) break
            selected.addFirst(line)
            used += added
        }
        val prefix = if (selected.size < lines.size) "[较早事件已省略；可用 session_event_search 分页检索]\n" else ""
        return prefix + selected.joinToString("\n")
    }

    fun read(
        sequence: Long,
        before: Int = 0,
        after: Int = 0,
        offsetChars: Int = 0,
        maxChars: Int = DEFAULT_SEARCH_RESULT_CHARS,
    ): String {
        val window = synchronized(lock) {
            if (nextSequence.get() == 0L) null
            else readWindowUnsafe(
                sequence = sequence,
                before = before.coerceIn(0, MAX_CONTEXT_LINES),
                after = after.coerceIn(0, MAX_CONTEXT_LINES),
            )
        }
        if (window == null) return "会话事件日志为空"
        if (window.isEmpty()) return "事件不存在：$sequence"
        val offset = offsetChars.coerceAtLeast(0).toLong()
        val budget = maxChars.coerceIn(MIN_SEARCH_RESULT_CHARS, MAX_SEARCH_RESULT_CHARS)
        val page = StringBuilder(budget)
        var cursor = 0L
        window.forEachIndexed { index, event ->
            val row = (if (index == 0) "" else "\n") + json.encodeToString(SessionEvent.serializer(), event)
            val rowEnd = cursor + row.length
            if (rowEnd > offset && page.length < budget) {
                val start = (offset - cursor).coerceAtLeast(0).toInt()
                val count = minOf(row.length - start, budget - page.length)
                page.append(row, start, start + count)
            }
            cursor = rowEnd
        }
        if (offset >= cursor) return "事件读取游标超出范围：$offsetChars（总字符数 $cursor）"
        if (offset == 0L && cursor <= budget) return page.toString()
        val footer = if (offset + page.length < cursor) {
            "\n[结果已分页；保持 seq/before/after 不变，继续调用 session_event_read，并传 offset_chars="
        } else ""
        if (footer.isEmpty()) return page.toString()
        val suffix = "]"
        var bodyLength = minOf(page.length, budget - footer.length - suffix.length - 20)
        if (bodyLength > 0 && page[bodyLength - 1].isHighSurrogate()) bodyLength--
        var nextOffset = offset + bodyLength
        while (bodyLength + footer.length + nextOffset.toString().length + suffix.length > budget) {
            bodyLength--
            nextOffset--
        }
        return page.substring(0, bodyLength) + footer + nextOffset + suffix
    }

    /**
     * Return the newest event matching [types] and [predicate].
     *
     * This is intentionally newest-first and stops at the first match. Hot attribution paths can
     * therefore resolve a recent call/run without replaying the complete append-only archive.
     */
    fun latestMatching(
        types: Set<String>,
        beforeSequenceExclusive: Long = Long.MAX_VALUE,
        predicate: (JsonObject) -> Boolean,
    ): SessionEvent? = synchronized(lock) {
        require(types.isNotEmpty()) { "事件类型集合不能为空" }
        for (source in orderedFilesUnsafe().asReversed()) {
            var found: SessionEvent? = null
            val completed = forEachEventReverseUnsafe(source) { event ->
                if (
                    event.sequence < beforeSequenceExclusive &&
                    event.type in types &&
                    predicate(event.data)
                ) {
                    found = event
                    false
                } else {
                    true
                }
            }
            if (!completed) return@synchronized found
        }
        null
    }

    fun latest(
        type: String,
        beforeSequenceExclusive: Long = Long.MAX_VALUE,
    ): SessionEvent? {
        require(type.isNotBlank()) { "事件类型不能为空" }
        return latestMatching(setOf(type), beforeSequenceExclusive) { true }
    }

    fun latestOf(types: Set<String>): SessionEvent? =
        latestMatching(types) { true }

    fun clear() {
        synchronized(lock) {
            val prefix = "${file.name}.part-"
            file.parentFile?.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith(prefix) }
                .forEach { it.delete() }
            file.parentFile?.mkdirs()
            file.writeText("")
            nextSequence.set(0L)
            CompressedEventSegmentCache.invalidate(pathKey)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        PATH_STATES.computeIfPresent(pathKey) { _, current ->
            if (current !== pathState) {
                current
            } else {
                synchronized(current.lock) {
                    current.references -= 1
                    check(current.references >= 0) { "事件日志共享路径引用计数异常" }
                    if (current.references == 0) CompressedEventSegmentCache.invalidate(pathKey)
                    current.takeIf { it.references > 0 }
                }
            }
        }
    }

    /**
     * Open a stable byte snapshot of every durable event file under the append/rotation lock.
     *
     * File descriptors remain attached to the same inode if a later append rotates or compresses
     * the path. [SessionEventFileSnapshot.length] freezes the visible byte boundary, so callers can
     * release this lock immediately and stream a consistent export without blocking future turns.
     */
    fun openDurableFileSnapshot(): List<SessionEventFileSnapshot> = synchronized(lock) {
        val opened = mutableListOf<SessionEventFileSnapshot>()
        try {
            orderedFilesUnsafe().forEach { source ->
                opened += SessionEventFileSnapshot(
                    name = source.name,
                    lastModified = source.lastModified(),
                    length = source.length(),
                    input = source.inputStream().buffered(),
                )
            }
            opened
        } catch (error: Exception) {
            opened.forEach { runCatching { it.close() } }
            throw error
        }
    }

    /** Compress a bounded amount of old history without requiring another message in this session. */
    fun archiveLegacySegments(limit: Int = 1): Int = synchronized(lock) {
        require(limit in 1..16) { "单次归档分片数须在 1..16 之间" }
        var archived = 0
        segmentFilesUnsafe().filter { !it.name.endsWith(COMPRESSED_SUFFIX) }
            .take(limit).forEach { source ->
                compressSegmentUnsafe(source)
                archived++
            }
        archived
    }

    /**
     * Recover the next sequence from the newest valid row only.
     *
     * Each segment is bounded by [maxBytes] and sequences are monotonic across rotations, so
     * replaying every historical JSON row at startup is unnecessary. Scanning newest files
     * backwards keeps restart cost bounded by one segment in the normal case while still
     * tolerating a torn/corrupt tail.
     */
    /**
     * Remove an incomplete active JSONL tail before another event is appended.
     *
     * Readers deliberately tolerate malformed rows, but blindly appending after a crash-torn row
     * would concatenate the next valid event to that fragment and lose both rows. Repair only the
     * mutable active segment while holding the shared path lock; immutable rotated segments remain
     * untouched and are still handled by tolerant readers.
     */
    private fun repairTornActiveTailUnsafe() {
        if (!file.isFile || file.length() == 0L) return
        RandomAccessFile(file, "rw").use { active ->
            val length = active.length()
            active.seek(length - 1L)
            if (active.readByte().toInt() == '\n'.code) return

            var cursor = length - 1L
            var truncateTo = 0L
            while (cursor >= 0L) {
                active.seek(cursor)
                if (active.readByte().toInt() == '\n'.code) {
                    truncateTo = cursor + 1L
                    break
                }
                cursor--
            }
            active.setLength(truncateTo)
        }
        malformedRows.incrementAndGet()
        diagnosticSink("torn-tail-truncated", null)
    }

    private fun readNextSequence(): Long {
        for (source in orderedFilesUnsafe().asReversed()) {
            val latest = readLastValidEventUnsafe(source) ?: continue
            return latest.sequence + 1L
        }
        return 0L
    }

    private fun readFirstValidEventUnsafe(source: File): SessionEvent? {
        if (!source.isFile || source.length() == 0L) return null
        try {
            source.eventReader().useLines { lines ->
                lines.forEach { line ->
                    decodeEventOrNull(line)?.let { return it }
                }
            }
        } catch (error: Exception) {
            reportSegmentReadFailure(source, error)
        }
        return null
    }

    private fun readLastValidEventUnsafe(source: File): SessionEvent? {
        if (!source.isFile || source.length() == 0L) return null
        if (source.name.endsWith(COMPRESSED_SUFFIX)) {
            var latest: SessionEvent? = null
            source.forEachEventLine { line ->
                decodeEventOrNull(line)?.let { latest = it }
            }
            return latest
        }
        RandomAccessFile(source, "r").use { input ->
            var cursor = input.length()
            val reversed = ByteArrayOutputStream()
            val buffer = ByteArray(REVERSE_READ_BUFFER_BYTES)
            while (cursor > 0L) {
                val chunkSize = minOf(buffer.size.toLong(), cursor).toInt()
                val start = cursor - chunkSize
                input.seek(start)
                input.readFully(buffer, 0, chunkSize)
                for (index in chunkSize - 1 downTo 0) {
                    val value = buffer[index].toInt() and 0xff
                    if (value == '\n'.code) {
                        if (reversed.size() > 0) {
                            decodeReversedLineUnsafe(reversed)?.let { return it }
                            reversed.reset()
                        }
                    } else {
                        reversed.write(value)
                    }
                }
                cursor = start
            }
            return decodeReversedLineUnsafe(reversed)
        }
    }

    private fun decodeReversedLineUnsafe(reversed: ByteArrayOutputStream): SessionEvent? {
        if (reversed.size() == 0) return null
        val bytes = reversed.toByteArray()
        bytes.reverse()
        return decodeEventOrNull(String(bytes, Charsets.UTF_8).trimEnd('\r'))
    }

    /**
     * Visit one segment from newest row to oldest without materializing the segment.
     *
     * Returning false from [visitor] stops immediately, allowing callers such as [pageBefore] to
     * bound both decoding work and temporary allocations to the requested page.
     */
    private fun forEachEventReverseUnsafe(
        source: File,
        visitor: (SessionEvent) -> Boolean,
    ): Boolean {
        if (!source.isFile || source.length() == 0L) return true
        if (source.name.endsWith(COMPRESSED_SUFFIX)) {
            val bytes = try {
                CompressedEventSegmentCache.read(source, maxEventBytes + maxBytes + 1L)
            } catch (error: Exception) {
                reportSegmentReadFailure(source, error)
                return true
            }
            var end = bytes.size
            while (end > 0) {
                if (bytes[end - 1] == '\n'.code.toByte()) { end -= 1; continue }
                var start = end - 1
                while (start >= 0 && bytes[start] != '\n'.code.toByte()) start -= 1
                val line = String(bytes, start + 1, end - start - 1, Charsets.UTF_8).trimEnd('\r')
                val event = decodeEventOrNull(line)
                if (event != null && !visitor(event)) return false
                end = start
            }
            return true
        }
        RandomAccessFile(source, "r").use { input ->
            var cursor = input.length()
            val reversed = ByteArrayOutputStream()
            val buffer = ByteArray(REVERSE_READ_BUFFER_BYTES)

            fun visitBufferedLine(): Boolean {
                val event = decodeReversedLineUnsafe(reversed)
                reversed.reset()
                return event?.let(visitor) ?: true
            }

            while (cursor > 0L) {
                val chunkSize = minOf(buffer.size.toLong(), cursor).toInt()
                val start = cursor - chunkSize
                input.seek(start)
                input.readFully(buffer, 0, chunkSize)
                for (index in chunkSize - 1 downTo 0) {
                    val value = buffer[index].toInt() and 0xff
                    if (value == '\n'.code) {
                        if (reversed.size() > 0 && !visitBufferedLine()) return false
                    } else {
                        reversed.write(value)
                    }
                }
                cursor = start
            }
            return reversed.size() == 0 || visitBufferedLine()
        }
    }

    private fun readWindowUnsafe(
        sequence: Long,
        before: Int,
        after: Int,
    ): List<SessionEvent> {
        val sources = orderedFilesUnsafe()
        if (sources.isEmpty()) return emptyList()

        var sourceIndex = -1
        for (index in sources.indices) {
            val last = readLastValidEventUnsafe(sources[index]) ?: continue
            if (sequence <= last.sequence) {
                sourceIndex = index
                break
            }
        }
        if (sourceIndex < 0) return emptyList()

        // Keep only the requested prefix while scanning the target segment. A point read with
        // before/after <= MAX_CONTEXT_LINES must not materialize an entire 8 MiB segment.
        val localBefore = ArrayDeque<SessionEvent>(before.coerceAtLeast(1))
        val window = mutableListOf<SessionEvent>()
        var found = false
        var afterRemaining = after
        val targetSource = sources[sourceIndex]
        try {
            targetSource.eventReader().useLines { lines ->
                val iterator = lines.iterator()
                while (iterator.hasNext()) {
                    val event = decodeEventOrNull(iterator.next()) ?: continue
                    if (!found) {
                        if (event.sequence == sequence) {
                            found = true
                            window.addAll(localBefore)
                            window += event
                        } else if (before > 0) {
                            if (localBefore.size >= before) localBefore.removeFirst()
                            localBefore.addLast(event)
                        }
                    } else if (afterRemaining > 0) {
                        window += event
                        afterRemaining -= 1
                    } else {
                        break
                    }
                }
            }
        } catch (error: Exception) {
            reportSegmentReadFailure(targetSource, error)
        }
        if (!found) return emptyList()

        var beforeMissing = (before - localBefore.size).coerceAtLeast(0)
        var left = sourceIndex - 1
        while (beforeMissing > 0 && left >= 0) {
            val tail = ArrayDeque<SessionEvent>(beforeMissing)
            sources[left].forEachEventLine { line ->
                val event = decodeEventOrNull(line) ?: return@forEachEventLine
                if (tail.size >= beforeMissing) tail.removeFirst()
                tail.addLast(event)
            }
            window.addAll(0, tail)
            beforeMissing -= tail.size
            left -= 1
        }

        var right = sourceIndex + 1
        while (afterRemaining > 0 && right < sources.size) {
            val source = sources[right]
            try {
                source.eventReader().useLines { lines ->
                    val iterator = lines.iterator()
                    while (afterRemaining > 0 && iterator.hasNext()) {
                        val event = decodeEventOrNull(iterator.next()) ?: continue
                        window += event
                        afterRemaining -= 1
                    }
                }
            } catch (error: Exception) {
                reportSegmentReadFailure(source, error)
            }
            right += 1
        }
        return window
    }

    private inline fun forEachAfterUnsafe(
        sequenceExclusive: Long,
        crossinline visitor: (SessionEvent) -> Unit,
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
            source.forEachEventLine { line ->
                val event = decodeEventOrNull(line) ?: return@forEachEventLine
                if (event.sequence > sequenceExclusive) visitor(event)
            }
        }
    }

    private fun decodeEventOrNull(line: String): SessionEvent? =
        runCatching { json.decodeFromString(SessionEvent.serializer(), line) }
            .getOrElse { error ->
                malformedRows.incrementAndGet()
                reportDiagnostic("malformed-row", error)
                null
            }

    private fun reportSegmentReadFailure(source: File, error: Throwable) {
        segmentReadFailures.incrementAndGet()
        reportDiagnostic("segment-read-failed:${source.name}", error)
    }

    private fun reportArchiveFailure(source: String, error: Throwable) {
        archiveFailures.incrementAndGet()
        reportDiagnostic("archive-failed:$source", error)
    }

    private fun reportDiagnostic(kind: String, error: Throwable?) {
        runCatching { diagnosticSink(kind, error) }
    }

    private fun readEventsUnsafe(): List<SessionEvent> {
        val events = mutableListOf<SessionEvent>()
        for (source in orderedFilesUnsafe()) {
            source.forEachEventLine { line ->
                decodeEventOrNull(line)?.let(events::add)
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
            .filter { it.isFile && it.name.startsWith(prefix) && SEGMENT_NAME.matches(it.name.removePrefix(prefix)) }
            .mapNotNull { candidate ->
                candidate.name.removePrefix(prefix).removeSuffix(COMPRESSED_SUFFIX).toIntOrNull()
                    ?.let { it to candidate }
            }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .values.map { copies -> copies.firstOrNull { !it.name.endsWith(COMPRESSED_SUFFIX) } ?: copies.first() }
    }

    private fun rotateActiveSegment() {
        if (!file.isFile || file.length() == 0L) return
        val parent = file.parentFile ?: error("事件日志缺少父目录")
        parent.mkdirs()
        val prefix = "${file.name}.part-"
        val nextIndex = segmentFilesUnsafe()
            .mapNotNull { it.name.removePrefix(prefix).removeSuffix(COMPRESSED_SUFFIX).toIntOrNull() }
            .maxOrNull()
            ?.plus(1)
            ?: 1
        val target = File(parent, "$prefix${nextIndex.toString().padStart(6, '0')}")
        try {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(file.toPath(), target.toPath())
        }
        runCatching(onSegmentRotated)
            .onFailure { error -> reportArchiveFailure("archive-signal", error) }
    }

    /** Publish the archive before deleting the original; an interrupted migration keeps the raw segment. */
    private fun compressSegmentUnsafe(source: File) {
        val compressed = File(source.path + COMPRESSED_SUFFIX)
        val temporary = File(source.path + ".gz.tmp")
        try {
            source.inputStream().buffered().use { input ->
                GZIPOutputStream(temporary.outputStream().buffered()).use { output -> input.copyTo(output) }
            }
            // Verify the whole stream and CRC before dropping the durable source.
            var decodedBytes = 0L
            GZIPInputStream(temporary.inputStream().buffered()).use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    decodedBytes += count
                }
            }
            check(decodedBytes == source.length()) { "事件归档校验失败" }
            try {
                Files.move(temporary.toPath(), compressed.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), compressed.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            check(source.delete()) { "事件原分片删除失败" }
        } finally {
            temporary.delete()
        }
    }

    private fun File.eventReader() =
        if (name.endsWith(COMPRESSED_SUFFIX)) GZIPInputStream(inputStream().buffered()).bufferedReader()
        else bufferedReader()

    private inline fun File.forEachEventLine(block: (String) -> Unit) {
        try {
            eventReader().useLines { lines -> lines.forEach(block) }
        } catch (error: Exception) {
            reportSegmentReadFailure(this, error)
        }
    }

    private class SharedPathState {
        val lock = Any()
        val nextSequence = AtomicLong(UNINITIALIZED_SEQUENCE)
        var references: Int = 0
    }

    private companion object {
        const val UNINITIALIZED_SEQUENCE = Long.MIN_VALUE
        val PATH_STATES = ConcurrentHashMap<String, SharedPathState>()
        const val DEFAULT_MAX_BYTES = 8L * 1024L * 1024L
        const val DEFAULT_MAX_EVENT_BYTES = 32L * 1024L * 1024L
        const val MIN_MAX_BYTES = 512L
        const val MAX_READ_LINES = 200
        const val MAX_SEARCH_RESULTS = 100
        const val MIN_SEARCH_RESULT_CHARS = 4_096
        const val DEFAULT_SEARCH_RESULT_CHARS = 48_000
        const val MAX_SEARCH_RESULT_CHARS = 48_000
        const val MAX_SEARCH_EVENT_CHARS = 8_192
        const val MAX_CONTEXT_LINES = 20
        const val DEFAULT_PAGE_EVENTS = 80
        const val MAX_PAGE_EVENTS = 200
        const val REVERSE_READ_BUFFER_BYTES = 8 * 1024
        const val COMPRESSED_SUFFIX = ".gz"
        val SEGMENT_NAME = Regex("[0-9]+(?:\\.gz)?")
    }
}
