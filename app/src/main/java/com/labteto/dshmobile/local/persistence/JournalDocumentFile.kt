package com.labteto.dshmobile.local.persistence

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Storage mechanics only. The domain supplies its codec, diff and idempotent reducer. */
internal class JournalDocumentFile<D, M>(
    private val file: File,
    private val journal: File,
    private val empty: () -> D,
    private val decodeDocument: (String) -> D,
    private val encodeDocument: (D) -> String,
    private val decodeMutation: (String) -> M,
    private val encodeMutation: (M) -> String,
    private val diff: (D, D) -> M?,
    private val reduce: (D, M) -> D,
    private val onDamagedTail: () -> Unit,
    private val afterSnapshotStage: (SnapshotCommitStage) -> Unit = {},
) {
    private val root = requireNotNull(file.parentFile)
    private val backup = File(root, file.name + ".bak")
    private val recoveryRequired = File(root, file.name + ".recovery-required")
    private val durable = RecoveringDocumentFile(file)
    private var cached: D? = null
    private var cachedStamp: DocumentFileStamp? = null

    @Synchronized
    fun read(): D {
        cached?.takeIf { cachedStamp == stamp() }?.let { return it }
        var document = durable.read(empty, decodeDocument)
        if (journal.isFile && journal.length() > 0) {
            var validBytes = 0L
            journal.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val mutation = runCatching { decodeMutation(line) }.getOrNull() ?: break
                    document = reduce(document, mutation)
                    validBytes += (line + "\n").toByteArray().size
                }
            }
            if (validBytes < journal.length()) {
                val damaged = File.createTempFile(journal.name + ".corrupt-", ".quarantine", root)
                DurableFileCommit.replace(damaged, journal.readBytes())
                RandomAccessFile(journal, "rw").use { it.setLength(validBytes); it.fd.sync() }
                onDamagedTail()
            } else if (validBytes > journal.length()) {
                // A valid last record without a newline must not merge with the next appended JSON.
                FileOutputStream(journal, true).use { it.write('\n'.code); it.fd.sync() }
            }
        }
        cached = document
        cachedStamp = stamp()
        return document
    }

    @Synchronized
    fun write(document: D) {
        val previous = read()
        if (!file.isFile) {
            snapshot(document)
            return
        }
        val mutation = diff(previous, document) ?: return
        FileOutputStream(journal, true).use { output ->
            output.write((encodeMutation(mutation) + "\n").toByteArray())
            output.fd.sync()
        }
        DurableFileCommit.syncDirectory(root)
        cached = document
        cachedStamp = stamp()
        if (journal.length() >= MAX_JOURNAL_BYTES || lineCountAtMost(MAX_MUTATIONS + 1) > MAX_MUTATIONS) {
            snapshot(document)
        }
    }

    private fun snapshot(document: D) {
        commitSnapshotAndClearJournal(file, backup, journal, encodeDocument(document),
            validate = { runCatching { decodeDocument(it) }.isSuccess }, afterStage = afterSnapshotStage)
        cached = document
        cachedStamp = stamp()
    }

    private fun stamp() = DocumentFileStamp.of(file, backup, recoveryRequired, journal)

    private fun lineCountAtMost(limit: Int): Int = journal.bufferedReader().useLines { it.take(limit).count() }

    private companion object {
        const val MAX_JOURNAL_BYTES = 512L * 1024L
        const val MAX_MUTATIONS = 128
    }
}
