package com.labteto.dshmobile.automation

import com.labteto.dshmobile.observability.AppLog
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class AutomationDocument(
    val version: Int = 1,
    val tasks: List<AutomationTask> = emptyList(),
)

@Serializable
private data class AutomationJournalMutation(
    val upserts: List<AutomationTask> = emptyList(),
    val removedIds: List<String> = emptyList(),
)

/**
 * Durable storage boundary for Automation.
 *
 * The snapshot is a rebuildable base and the WAL carries small task mutations between compactions.
 * Automation scheduling/business rules stay in [AutomationStore].
 */
internal class AutomationDocumentStore(
    private val file: File,
    private val json: Json,
) {
    private val root = requireNotNull(file.parentFile) { "自动任务存储必须位于目录中" }
    private val backup = File(root, file.name + ".bak")
    private val journal = File(root, file.name + ".wal.jsonl")
    private var cachedDocument: AutomationDocument? = null
    private var cachedStamp: DocumentStamp? = null

    init {
        root.mkdirs()
    }

    @Synchronized
    fun read(): AutomationDocument {
        val stamp = documentStamp()
        cachedDocument?.takeIf { cachedStamp == stamp }?.let { return it }

        val base = decodeDocument(file) ?: when {
            !file.isFile && !backup.isFile -> AutomationDocument()
            else -> recoverSnapshot()
        }
        val document = replayJournal(base)
        cachedDocument = document
        cachedStamp = documentStamp()
        return document
    }

    @Synchronized
    fun write(document: AutomationDocument) {
        root.mkdirs()
        val previous = read()
        if (!file.isFile && previous.tasks.isEmpty()) {
            writeSnapshot(document)
            return
        }

        val before = previous.tasks.associateBy(AutomationTask::id)
        val after = document.tasks.associateBy(AutomationTask::id)
        val mutation = AutomationJournalMutation(
            upserts = document.tasks.filter { task -> before[task.id] != task },
            removedIds = before.keys.filter { it !in after },
        )
        if (mutation.upserts.isEmpty() && mutation.removedIds.isEmpty()) return

        appendMutation(mutation)
        cachedDocument = document
        cachedStamp = documentStamp()

        if (
            journal.length() >= MAX_JOURNAL_BYTES ||
            journalLineCountAtMost(MAX_JOURNAL_MUTATIONS + 1) > MAX_JOURNAL_MUTATIONS
        ) {
            writeSnapshot(document)
        }
    }

    private fun recoverSnapshot(): AutomationDocument {
        val corrupt = File(root, "automations.corrupt-${System.currentTimeMillis()}.json")
        if (file.isFile) {
            runCatching { file.copyTo(corrupt, overwrite = false) }
        }
        val recovered = decodeDocument(backup) ?: throw IllegalStateException(
            "自动任务存储已损坏，主文件与备份均无法读取；损坏数据已保留供恢复",
        )
        runCatching { backup.copyTo(file, overwrite = true) }
        return recovered
    }

    private fun appendMutation(mutation: AutomationJournalMutation) {
        val encoded = json.encodeToString(AutomationJournalMutation.serializer(), mutation) + "\n"
        FileOutputStream(journal, true).use { output ->
            output.write(encoded.toByteArray())
            output.fd.sync()
        }
    }

    private fun replayJournal(base: AutomationDocument): AutomationDocument {
        if (!journal.isFile || journal.length() == 0L) return base
        val tasks = base.tasks.associateByTo(linkedMapOf(), AutomationTask::id)
        var validBytes = 0L
        journal.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val encodedBytes = (line + "\n").toByteArray().size.toLong()
                val mutation = runCatching {
                    json.decodeFromString(AutomationJournalMutation.serializer(), line)
                }.getOrNull() ?: break
                mutation.removedIds.forEach(tasks::remove)
                mutation.upserts.forEach { task -> tasks[task.id] = task }
                validBytes += encodedBytes
            }
        }
        if (validBytes < journal.length()) {
            val damaged = File(root, "automations.wal.corrupt-${System.currentTimeMillis()}.jsonl")
            runCatching { journal.copyTo(damaged, overwrite = false) }
            java.io.RandomAccessFile(journal, "rw").use { it.setLength(validBytes) }
            AppLog.warn("AutomationStore", "自动任务 WAL 检测到残损尾部，已截断并保留损坏副本")
        }
        return AutomationDocument(tasks = tasks.values.toList())
    }

    private fun decodeDocument(source: File): AutomationDocument? {
        if (!source.isFile) return null
        return runCatching {
            json.decodeFromString(AutomationDocument.serializer(), source.readText())
        }.getOrNull()
    }

    private fun writeSnapshot(document: AutomationDocument) {
        root.mkdirs()
        val temporary = File(root, file.name + ".tmp")
        temporary.writeText(json.encodeToString(AutomationDocument.serializer(), document))
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
        check(decodeDocument(file) != null) { "自动任务快照写入后校验失败" }

        // Snapshot 与 backup 必须使用同一 WAL 基线，再清空 WAL。
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
