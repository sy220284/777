package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.WORK_TOOL_RECOVERY_PAGE_BYTES
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Private spill storage for model-facing tool output retention.
 *
 * Spill files never enter the user workspace or Android backup. They are addressed by the original
 * tool call id through a hash, bounded per item, and removed with their owning Session.
 */
internal class LocalToolOutputStore(
    private val root: File,
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    private val maxFilesPerSession: Int = DEFAULT_MAX_FILES_PER_SESSION,
    private val maxSessionBytes: Long = DEFAULT_MAX_SESSION_BYTES,
    private val maxGlobalBytes: Long = DEFAULT_MAX_GLOBAL_BYTES,
    private val deleteFile: (File) -> Boolean = { it.delete() },
) {
    init {
        root.mkdirs()
    }

    private var knownGlobalBytes: Long? = null

    data class Stored(val bytes: Int)

    @Synchronized
    fun store(sessionId: String, callId: String, content: String): Stored? {
        // Do not allocate a second large UTF-8 array to retain a large tool result.
        // Existing session/global byte budgets also bound each individual result.
        val byteBudget = minOf(maxOutputBytes.toLong(), maxSessionBytes, maxGlobalBytes)
        if (byteBudget <= 0L) return null

        val dir = sessionDir(sessionId).apply { mkdirs() }
        val target = File(dir, key(callId) + ".txt")
        val previousBytes = target.takeIf(File::isFile)?.length() ?: 0L
        val cachedBefore = knownGlobalBytes
        val temp = File(dir, "." + target.name + ".tmp")
        var writtenBytes = 0L
        try {
            try {
                FileOutputStream(temp).use { output ->
                    val bounded = BudgetedToolOutputStream(output, byteBudget)
                    // Streaming encoder preserves UTF-8 character boundaries across chunks.
                    val writer = OutputStreamWriter(bounded, Charsets.UTF_8)
                    writer.write(content)
                    writer.flush()
                    output.fd.sync()
                    writtenBytes = bounded.written
                }
            } catch (_: ToolOutputBudgetExceeded) {
                // Keep the previous durable result; never replace it with a partial spill.
                return null
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
        target.setLastModified(System.currentTimeMillis())

        val removedFromSession = pruneSession(dir, target)
        var globalBytes = if (cachedBefore == null || !target.isFile) {
            measureGlobalBytes()
        } else {
            (
                cachedBefore -
                    previousBytes +
                    target.length() -
                    removedFromSession
            ).coerceAtLeast(0L)
        }
        if (globalBytes > maxGlobalBytes) {
            globalBytes = pruneGlobal(target)
        }
        knownGlobalBytes = globalBytes
        return if (target.isFile) Stored(writtenBytes.toInt()) else null
    }

    /**
     * The Work projection supplies the currently referenced call IDs. Persist only hashed IDs in
     * this private session directory so process restarts do not silently discard active references.
     * Pins influence eviction order but never defeat per-session/global byte limits.
     */
    @Synchronized
    fun retainActiveReferences(sessionId: String, callIds: Collection<String>) {
        val dir = sessionDir(sessionId).apply { mkdirs() }
        val target = File(dir, ACTIVE_REFERENCES_FILE)
        val names = callIds.filter(String::isNotBlank).distinct().takeLast(MAX_ACTIVE_REFERENCES)
            .map { key(it) + ".txt" }.toSet()
        val serialized = names.sorted().joinToString("\n")
        if (target.isFile && target.readText() == serialized) return
        if (names.isEmpty()) {
            target.delete()
            return
        }
        val temp = File(dir, ".active-refs.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(serialized.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun activeReferenceFileNames(dir: File): Set<String> {
        val manifest = File(dir, ACTIVE_REFERENCES_FILE)
        if (!manifest.isFile) return emptySet()
        return manifest.readLines().take(MAX_ACTIVE_REFERENCES).toSet()
    }

    @Synchronized
    fun read(
        sessionId: String,
        callId: String,
        startByte: Int = 0,
        maxBytes: Int = DEFAULT_READ_BYTES,
        visibleLimitBytes: Int = WORK_TOOL_RECOVERY_PAGE_BYTES,
    ): String {
        require(startByte >= 0) { "start_byte 必须大于等于 0" }
        require(maxBytes in MIN_READ_BYTES..MAX_READ_BYTES) {
            "max_bytes 必须在 $MIN_READ_BYTES–$MAX_READ_BYTES 之间"
        }
        // A page larger than what the model can actually see would be re-truncated downstream while
        // next_byte still advanced by the requested amount, silently skipping the trimmed bytes.
        val pageBytes = maxBytes.coerceAtMost(visibleLimitBytes.coerceAtLeast(MIN_READ_BYTES))

        val source = File(sessionDir(sessionId), key(callId) + ".txt")
        if (!source.isFile) return missingResult(callId)
        source.setLastModified(System.currentTimeMillis())

        val totalBytes = source.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (startByte >= totalBytes) return "工具结果没有第 $startByte 个 UTF-8 字节"

        RandomAccessFile(source, "r").use { file ->
            var alignedStart = startByte
            if (alignedStart > 0) {
                while (alignedStart < totalBytes) {
                    file.seek(alignedStart.toLong())
                    val value = file.read()
                    if (value < 0 || (value and 0xC0) != 0x80) break
                    alignedStart += 1
                }
            }
            if (alignedStart >= totalBytes) return "工具结果没有可从第 $startByte 个字节开始读取的完整字符"

            val requested = minOf(pageBytes, totalBytes - alignedStart)
            val bytes = ByteArray(requested)
            file.seek(alignedStart.toLong())
            file.readFully(bytes)
            val safeLength = safeUtf8PrefixLength(bytes)
            if (safeLength <= 0) return "当前读取窗口无法容纳一个完整 UTF-8 字符，请增大 max_bytes"

            val nextByte = alignedStart + safeLength
            val body = String(bytes, 0, safeLength, Charsets.UTF_8)
            return buildString {
                append("工具调用 ").append(callId)
                append(" · UTF-8 字节 ").append(alignedStart)
                append("–").append(nextByte - 1)
                append(" / ").append(totalBytes)
                if (nextByte < totalBytes) append("（后续仍有内容）")
                append("\n\n")
                append(body)
                if (nextByte < totalBytes) {
                    append("\n\n继续读取可把 start_byte 设为 ")
                    append(nextByte)
                    append("。")
                }
            }
        }
    }

    /**
     * Bounded literal search of the retained UTF-8 result. Reads in chunks instead of materializing
     * a potentially multi-megabyte output just to locate a short diagnostic string.
     * Returned offsets can be passed directly to read(startByte=...).
     */
    @Synchronized
    fun search(sessionId: String, callId: String, query: String, startByte: Int = 0): String {
        require(startByte >= 0) { "start_byte 必须大于等于 0" }
        val needle = query.toByteArray(Charsets.UTF_8)
        require(needle.isNotEmpty() && needle.size <= MAX_SEARCH_QUERY_BYTES) {
            "query 必须为 1–$MAX_SEARCH_QUERY_BYTES 个 UTF-8 字节"
        }
        val source = File(sessionDir(sessionId), key(callId) + ".txt")
        if (!source.isFile) return missingResult(callId)
        val total = source.length()
        if (startByte >= total) return "工具结果没有第 $startByte 个 UTF-8 字节"
        val prefix = IntArray(needle.size)
        var p = 0
        for (i in 1 until needle.size) {
            while (p > 0 && needle[i] != needle[p]) p = prefix[p - 1]
            if (needle[i] == needle[p]) p++
            prefix[i] = p
        }
        val matches = mutableListOf<Long>()
        var more = false
        RandomAccessFile(source, "r").use { file ->
            file.seek(startByte.toLong())
            var position = startByte.toLong()
            var matched = 0
            val buffer = ByteArray(8 * 1024)
            scan@ while (true) {
                val count = file.read(buffer)
                if (count < 0) break
                for (i in 0 until count) {
                    val value = buffer[i]
                    while (matched > 0 && value != needle[matched]) matched = prefix[matched - 1]
                    if (value == needle[matched]) matched++
                    if (matched == needle.size) {
                        val offset = position - needle.size + 1L
                        if (matches.size >= MAX_SEARCH_MATCHES) {
                            more = true
                            break@scan
                        }
                        matches += offset
                        matched = prefix[matched - 1]
                    }
                    position++
                }
            }
        }
        return buildString {
            append("工具调用 ").append(callId).append(" · 字面量查询 ")
                .append(query).append(" · 原文 ").append(total).append(" 字节")
            if (matches.isEmpty()) {
                append("\n没有匹配内容。")
            } else {
                append("\n匹配位置（UTF-8 字节偏移）：")
                matches.forEach { offset ->
                    append("\n- ").append(offset).append("；使用 start_byte=")
                        .append(offset.coerceAtMost(Int.MAX_VALUE.toLong()))
                        .append(" 读取附近原文")
                }
                if (more) append("\n还有更多匹配；使用更靠后的 start_byte 继续搜索。")
            }
        }
    }

    private fun missingResult(callId: String): String =
        "未找到该工具调用的完整保留结果：$callId（可能已经容量淘汰、会话清理或未成功落盘；请重新定位可靠证据）"

    private fun safeUtf8PrefixLength(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        var leadIndex = bytes.lastIndex
        while (leadIndex >= 0 && isUtf8Continuation(bytes[leadIndex])) leadIndex -= 1
        if (leadIndex < 0) return 0
        val lead = bytes[leadIndex].toInt() and 0xFF
        val expected = when {
            lead < 0x80 -> 1
            lead in 0xC2..0xDF -> 2
            lead in 0xE0..0xEF -> 3
            lead in 0xF0..0xF4 -> 4
            else -> 1
        }
        return if (bytes.size - leadIndex < expected) leadIndex else bytes.size
    }

    private fun isUtf8Continuation(value: Byte): Boolean =
        (value.toInt() and 0xC0) == 0x80

    @Synchronized
    fun deleteSession(sessionId: String) {
        val dir = sessionDir(sessionId)
        val removedBytes = if (knownGlobalBytes != null) storedBytes(dir) else 0L
        if (dir.exists()) {
            check(dir.deleteRecursively() && !dir.exists()) {
                "会话工具结果未完全删除：$sessionId"
            }
        }
        // Never report reclaimed storage for bytes which still physically exist.
        knownGlobalBytes = knownGlobalBytes?.let { (it - removedBytes).coerceAtLeast(0L) }
    }

    private fun sessionDir(sessionId: String): File = File(root, key(sessionId))

    private fun pruneSession(dir: File, protectedFile: File): Long {
        var keptBytes = 0L
        var removedBytes = 0L
        val active = activeReferenceFileNames(dir)
        dir.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.startsWith(".") }
            .sortedWith(
                compareByDescending<File> { file ->
                    when {
                        file == protectedFile -> 2
                        file.name in active -> 1
                        else -> 0
                    }
                }.thenByDescending(File::lastModified),
            )
            .forEachIndexed { index, file ->
                val length = file.length()
                val nextBytes = keptBytes + length
                if (index >= maxFilesPerSession || nextBytes > maxSessionBytes) {
                    if (deleteFile(file)) removedBytes += length
                } else {
                    keptBytes = nextBytes
                }
            }
        return removedBytes
    }

    private fun pruneGlobal(protectedFile: File): Long {
        val activeByDirectory = mutableMapOf<File, Set<String>>()
        var keptBytes = 0L
        root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .sortedWith(
                compareByDescending<File> { file ->
                    when {
                        file == protectedFile -> 2
                        file.name in activeByDirectory.getOrPut(file.parentFile) {
                            activeReferenceFileNames(file.parentFile)
                        } -> 1
                        else -> 0
                    }
                }.thenByDescending(File::lastModified),
            )
            .forEach { file ->
                val length = file.length()
                val nextBytes = keptBytes + length
                if (nextBytes > maxGlobalBytes) {
                    deleteFile(file)
                } else {
                    keptBytes = nextBytes
                }
            }
        root.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .filter { it.listFiles().isNullOrEmpty() }
            .forEach(File::delete)
        // Deletion may fail transiently (for example while a file is held by the OS). Re-measure
        // instead of caching the optimistic keptBytes value, otherwise a later write can skip
        // pruning even though the real store is still above the global budget.
        return measureGlobalBytes()
    }

    private fun measureGlobalBytes(): Long = storedBytes(root)

    private fun storedBytes(dir: File): Long =
        dir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .sumOf(File::length)

    private fun key(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    /** The existing byte budget is enforced while encoding; no extra per-run store. */
    private class BudgetedToolOutputStream(
        private val sink: OutputStream,
        private val budget: Long,
    ) : OutputStream() {
        var written: Long = 0L
            private set

        override fun write(value: Int) {
            if (written >= budget) throw ToolOutputBudgetExceeded()
            sink.write(value)
            written++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length.toLong() > budget - written) throw ToolOutputBudgetExceeded()
            sink.write(bytes, offset, length)
            written += length
        }

        override fun flush() = sink.flush()
    }

    private class ToolOutputBudgetExceeded : IOException("工具输出超过本次安全存储预算")

    companion object {
        const val DEFAULT_READ_BYTES = 4 * 1024
        const val MAX_READ_BYTES = 48 * 1024
        private const val MAX_SEARCH_QUERY_BYTES = 512
        private const val MAX_SEARCH_MATCHES = 10
        private const val ACTIVE_REFERENCES_FILE = ".active-tool-refs"
        private const val MAX_ACTIVE_REFERENCES = 16
        private const val MIN_READ_BYTES = 1_024
        // One output may use the existing session quota, rather than an obsolete 16 MiB cap.
        private const val DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024 * 1024
        private const val DEFAULT_MAX_FILES_PER_SESSION = 64
        private const val DEFAULT_MAX_SESSION_BYTES = 64L * 1024L * 1024L
        private const val DEFAULT_MAX_GLOBAL_BYTES = 256L * 1024L * 1024L
    }
}
