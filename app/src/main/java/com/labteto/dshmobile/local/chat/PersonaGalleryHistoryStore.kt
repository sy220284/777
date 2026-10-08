package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.io.readBoundedLine

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.LinkedHashMap
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

internal data class PersonaGalleryHistoryPage(
    val messages: List<LocalHarnessMessage>,
    val totalCount: Int,
)

/**
 * Cold dialogue archive for one persona story.
 *
 * The hot gallery document only carries a small recent window. Full dialogue is append-only here,
 * so relationship/group-state writes never serialize old conversations. Full scans are reserved for
 * explicit save/export/delete maintenance, while UI history expansion reads newest rows backwards.
 */
internal class PersonaGalleryHistoryStore(
    private val root: File,
    private val json: Json,
) {
    init { root.mkdirs() }

    private data class CountStamp(val modified: Long, val length: Long, val rows: Int)
    private val rowCounts = object : LinkedHashMap<String, CountStamp>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CountStamp>?): Boolean =
            size > 64
    }

    private fun rememberCount(file: File, count: Int) {
        rowCounts[file.absolutePath] = CountStamp(file.lastModified(), file.length(), count)
    }

    @Synchronized
    fun merge(
        entryId: String,
        storyId: String,
        incoming: List<LocalHarnessMessage>,
    ): PersonaGalleryHistoryPage {
        val relevant = incoming
            .asSequence()
            .filter { it.role == "user" || it.role == "assistant" }
            .sortedBy(LocalHarnessMessage::createdAt)
            .toList()
        val file = archiveFile(entryId, storyId)
        repairTornTail(file)

        val known = linkedSetOf<String>()
        var total = 0
        if (file.isFile) {
            file.bufferedReader().use { reader ->
                while (true) {
                    val line = readBoundedLine(reader, MAX_ARCHIVE_LINE_BYTES) ?: break
                    decode(line)?.let { message ->
                        known += galleryMessageArchiveKey(message)
                        total++
                    }
                }
            }
        }

        val fresh = relevant.filter { known.add(galleryMessageArchiveKey(it)) }
        if (fresh.isNotEmpty()) {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                fresh.forEach { message ->
                    writer.append(json.encodeToString(LocalHarnessMessage.serializer(), message))
                    writer.newLine()
                }
                writer.flush()
            }
            // BufferedWriter closes before the explicit durability barrier.
            FileOutputStream(file, true).use { it.fd.sync() }
            total += fresh.size
        }
        rememberCount(file, total)
        return tailInternal(file, HOT_GALLERY_HISTORY_MESSAGES, knownTotal = total)
    }

    @Synchronized
    fun migrateIfNeeded(
        entryId: String,
        storyId: String,
        inlineHistory: List<LocalHarnessMessage>,
    ): PersonaGalleryHistoryPage {
        val file = archiveFile(entryId, storyId)
        if (!file.isFile || file.length() == 0L) {
            return merge(entryId, storyId, inlineHistory)
        }
        // A previous migration may have committed the archive but crashed before stripping the hot
        // document. Merge is idempotent by message archive key.
        return merge(entryId, storyId, inlineHistory)
    }

    @Synchronized
    fun tail(entryId: String, storyId: String, limit: Int): PersonaGalleryHistoryPage {
        val file = archiveFile(entryId, storyId)
        repairTornTail(file)
        return tailInternal(file, limit.coerceIn(1, MAX_HISTORY_PAGE))
    }

    @Synchronized
    fun all(entryId: String, storyId: String): List<LocalHarnessMessage> {
        val file = archiveFile(entryId, storyId)
        repairTornTail(file)
        if (!file.isFile) return emptyList()
        return buildList {
            file.bufferedReader().use { reader ->
                while (true) {
                    val line = readBoundedLine(reader, MAX_ARCHIVE_LINE_BYTES) ?: break
                    decode(line)?.let(::add)
                }
            }
        }
    }

    /** Verify the key before committing a durable exclusion; missing keys must be no-ops. */
    @Synchronized
    fun containsMessage(entryId: String, storyId: String, messageKey: String): Boolean {
        val file = archiveFile(entryId, storyId)
        repairTornTail(file)
        if (!file.isFile) return false
        return file.bufferedReader().use { reader ->
            while (true) {
                val line = readBoundedLine(reader, MAX_ARCHIVE_LINE_BYTES) ?: break
                val message = decode(line) ?: continue
                if (galleryMessageArchiveKey(message) == messageKey) return@use true
            }
            false
        }
    }

    @Synchronized
    fun deleteMessage(entryId: String, storyId: String, messageKey: String): Boolean {
        val file = archiveFile(entryId, storyId)
        repairTornTail(file)
        if (!file.isFile) return false
        val temporary = File(file.parentFile, file.name + ".tmp")
        var removed = false
        file.bufferedReader().use { input ->
            temporary.bufferedWriter().use { output ->
                while (true) {
                    val line = readBoundedLine(input, MAX_ARCHIVE_LINE_BYTES) ?: break
                    val message = decode(line) ?: continue
                    if (!removed && galleryMessageArchiveKey(message) == messageKey) {
                        removed = true
                    } else {
                        output.append(line)
                        output.newLine()
                    }
                }
            }
        }
        if (!removed) {
            temporary.delete()
            return false
        }
        rowCounts.remove(file.absolutePath)
        replace(temporary, file)
        return true
    }

    /**
     * Idempotent recovery of durable metadata tombstones after a crash between the
     * gallery write and the physical cold-archive rewrite.
     */
    @Synchronized
    fun pruneExcluded(entryId: String, storyId: String, excluded: Set<String>): Int {
        if (excluded.isEmpty()) return 0
        val file = archiveFile(entryId, storyId)
        repairTornTail(file)
        if (!file.isFile) return 0
        val temporary = File(file.parentFile, file.name + ".prune.tmp")
        var removed = 0
        try {
            file.bufferedReader().use { input ->
                temporary.bufferedWriter().use { output ->
                    while (true) {
                        val line = readBoundedLine(input, MAX_ARCHIVE_LINE_BYTES) ?: break
                        val message = decode(line)
                            ?: error("人物历史归档含有无法解析的记录，拒绝覆盖原数据")
                        if (galleryMessageArchiveKey(message) in excluded) {
                            removed++
                        } else {
                            output.append(line)
                            output.newLine()
                        }
                    }
                }
            }
            if (removed > 0) {
                rowCounts.remove(file.absolutePath)
                replace(temporary, file)
            }
            return removed
        } finally {
            if (temporary.isFile) temporary.delete()
        }
    }

    @Synchronized
    fun deleteStory(entryId: String, storyId: String) {
        val file = archiveFile(entryId, storyId)
        rowCounts.remove(file.absolutePath)
        if (file.exists()) {
            check(file.delete()) { "人物故事冷归档无法删除：${file.path}" }
        }
        file.parentFile
            ?.takeIf { it.isDirectory && it.listFiles().isNullOrEmpty() }
            ?.let { directory ->
                check(directory.delete()) { "人物故事冷归档目录无法删除：${directory.path}" }
            }
    }

    @Synchronized
    fun deleteEntry(entryId: String) {
        val directory = entryDirectory(entryId)
        if (!directory.isDirectory) return
        rowCounts.keys.removeAll { it.startsWith(directory.absolutePath + File.separator) }
        directory.walkBottomUp().forEach { target ->
            if (target.exists()) {
                check(target.delete()) { "人物冷归档无法删除：${target.path}" }
            }
        }
    }

    private fun tailInternal(
        file: File,
        limit: Int,
        knownTotal: Int? = null,
    ): PersonaGalleryHistoryPage {
        if (!file.isFile || file.length() == 0L) {
            return PersonaGalleryHistoryPage(emptyList(), knownTotal ?: 0)
        }
        val newestFirst = ArrayList<LocalHarnessMessage>(limit)
        RandomAccessFile(file, "r").use { input ->
            var cursor = input.length()
            val reversed = ByteArrayOutputStream()
            var skipTrailingNewline = true

            fun consume(): Boolean {
                if (reversed.size() == 0) return true
                val bytes = reversed.toByteArray()
                bytes.reverse()
                reversed.reset()
                val line = String(bytes, Charsets.UTF_8).trimEnd('\r')
                decode(line)?.let(newestFirst::add)
                return newestFirst.size < limit
            }

            while (cursor > 0L && newestFirst.size < limit) {
                cursor--
                input.seek(cursor)
                val value = input.read()
                if (value == '\n'.code) {
                    if (skipTrailingNewline) {
                        skipTrailingNewline = false
                    } else if (!consume()) {
                        break
                    }
                } else {
                    skipTrailingNewline = false
                    if (reversed.size() >= MAX_ARCHIVE_LINE_BYTES) {
                        error("人物冷归档单条消息超过安全上限")
                    }
                    reversed.write(value)
                }
            }
            if (newestFirst.size < limit) consume()
        }
        val total = knownTotal ?: countRows(file)
        return PersonaGalleryHistoryPage(newestFirst.asReversed(), total)
    }

    private fun countRows(file: File): Int {
        val length = file.length()
        val modified = file.lastModified()
        rowCounts[file.absolutePath]?.let { previous ->
            if (previous.length == length && previous.modified == modified) return previous.rows
        }
        var count = 0
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            var sawBytes = false
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) sawBytes = true
                for (index in 0 until read) if (buffer[index] == '\n'.code.toByte()) count++
            }
            if (sawBytes && file.length() > 0L) {
                RandomAccessFile(file, "r").use { tail ->
                    tail.seek(file.length() - 1L)
                    if (tail.read() != '\n'.code) count++
                }
            }
        }
        rememberCount(file, count)
        return count
    }

    private fun repairTornTail(file: File) {
        if (!file.isFile || file.length() == 0L) return
        RandomAccessFile(file, "rw").use { io ->
            io.seek(io.length() - 1L)
            if (io.read() == '\n'.code) return
            var cursor = io.length() - 1L
            while (cursor >= 0L) {
                io.seek(cursor)
                if (io.read() == '\n'.code) {
                    io.setLength(cursor + 1L)
                    return
                }
                cursor--
            }
            io.setLength(0L)
        }
    }

    private fun decode(line: String): LocalHarnessMessage? =
        runCatching { json.decodeFromString(LocalHarnessMessage.serializer(), line) }.getOrNull()

    private fun archiveFile(entryId: String, storyId: String): File =
        File(entryDirectory(entryId), safe(storyId) + ".jsonl")

    private fun entryDirectory(entryId: String): File =
        File(root, safe(entryId))

    private fun safe(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "_" }

    private fun replace(source: File, target: File) {
        target.parentFile?.mkdirs()
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse { error ->
            if (error !is AtomicMoveNotSupportedException) {
                // Retry without ATOMIC_MOVE for filesystems that report a generic provider error.
            }
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val HOT_GALLERY_HISTORY_MESSAGES = 32
        const val MAX_HISTORY_PAGE = 2_000
        private const val MAX_ARCHIVE_LINE_BYTES = 4 * 1024 * 1024
    }
}
