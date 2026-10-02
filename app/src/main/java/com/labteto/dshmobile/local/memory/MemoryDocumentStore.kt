package com.labteto.dshmobile.local.memory

import com.labteto.dshmobile.observability.AppLog
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class MemoryJournalMutation(
    val upserts: List<MemoryRecord> = emptyList(),
    val removedIds: List<String> = emptyList(),
)

/**
 * Durable storage boundary for Memory.
 *
 * Owns snapshot/WAL/cache/recovery only. Memory selection, provenance and recall semantics remain in
 * [MemoryStore] and `MemoryRecordMaintenance.kt`.
 */
internal class MemoryDocumentStore(
    private val root: File,
    private val json: Json,
) {
    init {
        root.mkdirs()
    }

    private val file = File(root, "memories.json")
    private val backup = File(root, "memories.json.bak")
    private val journal = File(root, "memories.wal.jsonl")
    private var cachedDocument: MemoryDocument? = null
    private var cachedStamp: DocumentStamp? = null

    @Synchronized
    fun read(): MemoryDocument {
        val stamp = documentStamp()
        cachedDocument?.takeIf { cachedStamp == stamp }?.let { return it }

        val base = decodeDocument(file) ?: when {
            !file.isFile && !backup.isFile -> MemoryDocument()
            else -> {
                val corrupt = File(root, "memories.corrupt-${System.currentTimeMillis()}.json")
                if (file.isFile) {
                    val moved = runCatching { file.renameTo(corrupt) }.getOrDefault(false)
                    if (!moved) runCatching { file.copyTo(corrupt, overwrite = false) }
                }
                decodeDocument(backup)?.also {
                    runCatching { backup.copyTo(file, overwrite = true) }
                } ?: throw IllegalStateException(
                    "长期记忆存储已损坏，主文件与备份均无法读取；损坏数据已保留供恢复",
                )
            }
        }
        val document = replayJournal(base)
        cachedDocument = document
        cachedStamp = documentStamp()
        return document
    }

    @Synchronized
    fun write(document: MemoryDocument) {
        root.mkdirs()
        val previous = read()
        if (!file.isFile && previous.records.isEmpty()) {
            writeSnapshot(document)
            return
        }

        val before = previous.records.associateBy(MemoryRecord::id)
        val after = document.records.associateBy(MemoryRecord::id)
        val mutation = MemoryJournalMutation(
            upserts = document.records.filter { record -> before[record.id] != record },
            removedIds = before.keys.filter { it !in after },
        )
        if (mutation.upserts.isEmpty() && mutation.removedIds.isEmpty()) return

        val encoded = json.encodeToString(MemoryJournalMutation.serializer(), mutation) + "\n"
        FileOutputStream(journal, true).use { output ->
            output.write(encoded.toByteArray())
            output.fd.sync()
        }
        cachedDocument = document
        cachedStamp = documentStamp()

        if (
            journal.length() >= MAX_JOURNAL_BYTES ||
            journalLineCountAtMost(MAX_JOURNAL_MUTATIONS + 1) > MAX_JOURNAL_MUTATIONS
        ) {
            writeSnapshot(document)
        }
    }

    private fun replayJournal(base: MemoryDocument): MemoryDocument {
        if (!journal.isFile || journal.length() == 0L) return base
        val records = base.records.associateByTo(linkedMapOf(), MemoryRecord::id)
        var validBytes = 0L
        journal.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val encodedBytes = (line + "\n").toByteArray().size.toLong()
                val mutation = runCatching {
                    json.decodeFromString(MemoryJournalMutation.serializer(), line)
                }.getOrNull() ?: break
                mutation.removedIds.forEach(records::remove)
                mutation.upserts.forEach { records[it.id] = it }
                validBytes += encodedBytes
            }
        }
        if (validBytes < journal.length()) {
            val damaged = File(root, "memories.wal.corrupt-${System.currentTimeMillis()}.jsonl")
            runCatching { journal.copyTo(damaged, overwrite = false) }
            java.io.RandomAccessFile(journal, "rw").use { it.setLength(validBytes) }
            AppLog.warn("MemoryStore", "长期记忆 WAL 检测到残损尾部，已截断并保留损坏副本")
        }
        return MemoryDocument(records = records.values.toList())
    }

    private fun decodeDocument(source: File): MemoryDocument? {
        if (!source.isFile) return null
        return runCatching {
            json.decodeFromString(MemoryDocument.serializer(), source.readText())
        }.getOrNull()
    }

    private fun writeSnapshot(document: MemoryDocument) {
        root.mkdirs()
        val temporary = File(root, file.name + ".tmp")
        temporary.writeText(json.encodeToString(MemoryDocument.serializer(), document))
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        check(decodeDocument(file) != null) { "长期记忆快照写入后校验失败" }

        // Snapshot 与 backup 必须是同一 WAL 基线。先同步 backup，再清空 WAL：
        // 任一中间崩溃窗口里，至少存在一组可重放的一致 base + WAL。
        file.copyTo(backup, overwrite = true)
        FileOutputStream(journal, false).use { it.fd.sync() }
        cachedDocument = document
        cachedStamp = documentStamp()
    }

    private fun journalLineCountAtMost(limit: Int): Int {
        if (!journal.isFile) return 0
        var count = 0
        journal.bufferedReader().useLines { lines ->
            val iterator = lines.iterator()
            while (iterator.hasNext() && count < limit) {
                iterator.next()
                count++
            }
        }
        return count
    }

    private fun documentStamp(): DocumentStamp = DocumentStamp(
        primaryModified = file.takeIf(File::isFile)?.lastModified() ?: -1L,
        primaryLength = file.takeIf(File::isFile)?.length() ?: -1L,
        backupModified = backup.takeIf(File::isFile)?.lastModified() ?: -1L,
        backupLength = backup.takeIf(File::isFile)?.length() ?: -1L,
        journalModified = journal.takeIf(File::isFile)?.lastModified() ?: -1L,
        journalLength = journal.takeIf(File::isFile)?.length() ?: -1L,
    )

    private data class DocumentStamp(
        val primaryModified: Long,
        val primaryLength: Long,
        val backupModified: Long,
        val backupLength: Long,
        val journalModified: Long,
        val journalLength: Long,
    )

    private companion object {
        const val MAX_JOURNAL_BYTES = 512L * 1024L
        const val MAX_JOURNAL_MUTATIONS = 128
    }
}
