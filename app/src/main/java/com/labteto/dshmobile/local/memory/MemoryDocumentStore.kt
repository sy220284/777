package com.labteto.dshmobile.local.memory

import com.labteto.dshmobile.local.persistence.JournalDocumentFile
import com.labteto.dshmobile.local.persistence.SnapshotCommitStage
import com.labteto.dshmobile.observability.AppLog
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class MemoryJournalMutation(
    val upserts: List<MemoryRecord> = emptyList(),
    val removedIds: List<String> = emptyList(),
)

/** Memory owns its mutation semantics; shared persistence owns the journal and durable snapshots. */
internal class MemoryDocumentStore(
    root: File,
    json: Json,
    afterSnapshotStage: (SnapshotCommitStage) -> Unit = {},
) {
    private val storage = JournalDocumentFile(
        file = File(root, "memories.json"), journal = File(root, "memories.wal.jsonl"),
        empty = ::MemoryDocument,
        decodeDocument = { json.decodeFromString(MemoryDocument.serializer(), it) },
        encodeDocument = { json.encodeToString(MemoryDocument.serializer(), it) },
        decodeMutation = { json.decodeFromString(MemoryJournalMutation.serializer(), it) },
        encodeMutation = { json.encodeToString(MemoryJournalMutation.serializer(), it) },
        diff = { before, after ->
            val previous = before.records.associateBy(MemoryRecord::id)
            val ids = after.records.mapTo(hashSetOf(), MemoryRecord::id)
            MemoryJournalMutation(after.records.filter { previous[it.id] != it }, previous.keys.filter { it !in ids })
                .takeUnless { it.upserts.isEmpty() && it.removedIds.isEmpty() }
        },
        reduce = { base, mutation ->
            val records = base.records.associateByTo(linkedMapOf(), MemoryRecord::id)
            mutation.removedIds.forEach(records::remove)
            mutation.upserts.forEach { records[it.id] = it }
            base.copy(records = records.values.toList())
        },
        onDamagedTail = { AppLog.warn("MemoryStore", "长期记忆 WAL 检测到残损尾部，已截断并保留损坏副本") },
        afterSnapshotStage = afterSnapshotStage,
    )
    fun read(): MemoryDocument = storage.read()
    fun write(document: MemoryDocument) = storage.write(document)
}
