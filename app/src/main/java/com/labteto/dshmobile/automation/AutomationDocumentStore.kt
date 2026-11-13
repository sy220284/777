package com.labteto.dshmobile.automation

import com.labteto.dshmobile.local.persistence.JournalDocumentFile
import com.labteto.dshmobile.local.persistence.SnapshotCommitStage
import com.labteto.dshmobile.observability.AppLog
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class AutomationDocument(
    val version: Int = 1,
    val tasks: List<AutomationTask> = emptyList(),
    val generationWatermark: Long = -1L,
)

@Serializable
private data class AutomationJournalMutation(
    val upserts: List<AutomationTask> = emptyList(),
    val removedIds: List<String> = emptyList(),
    val generationWatermark: Long? = null,
)

/** Automation owns task mutations and watermarks; persistence never interprets scheduling facts. */
internal class AutomationDocumentStore(
    file: File,
    json: Json,
    afterSnapshotStage: (SnapshotCommitStage) -> Unit = {},
) {
    private val storage = JournalDocumentFile(
        file = file, journal = File(file.parentFile, file.name + ".wal.jsonl"),
        empty = ::AutomationDocument,
        decodeDocument = { json.decodeFromString(AutomationDocument.serializer(), it) },
        encodeDocument = { json.encodeToString(AutomationDocument.serializer(), it) },
        decodeMutation = { json.decodeFromString(AutomationJournalMutation.serializer(), it) },
        encodeMutation = { json.encodeToString(AutomationJournalMutation.serializer(), it) },
        diff = { before, after ->
            val previous = before.tasks.associateBy(AutomationTask::id)
            val ids = after.tasks.mapTo(hashSetOf(), AutomationTask::id)
            AutomationJournalMutation(after.tasks.filter { previous[it.id] != it }, previous.keys.filter { it !in ids },
                after.generationWatermark.takeIf { it != before.generationWatermark })
                .takeUnless { it.upserts.isEmpty() && it.removedIds.isEmpty() && it.generationWatermark == null }
        },
        reduce = { base, mutation ->
            val tasks = base.tasks.associateByTo(linkedMapOf(), AutomationTask::id)
            // Preserve deleted generations before removal. Replaying a committed mutation is idempotent.
            val watermark = maxOf(base.generationWatermark, tasks.values.maxOfOrNull { it.scheduleGeneration } ?: -1L,
                mutation.generationWatermark ?: -1L, mutation.upserts.maxOfOrNull { it.scheduleGeneration } ?: -1L)
            mutation.removedIds.forEach(tasks::remove)
            mutation.upserts.forEach { tasks[it.id] = it }
            base.copy(tasks = tasks.values.toList(), generationWatermark = watermark)
        },
        onDamagedTail = { AppLog.warn("AutomationStore", "自动任务 WAL 检测到残损尾部，已截断并保留损坏副本") },
        afterSnapshotStage = afterSnapshotStage,
    )
    fun read(): AutomationDocument = storage.read()
    fun write(document: AutomationDocument) = storage.write(document)
}
