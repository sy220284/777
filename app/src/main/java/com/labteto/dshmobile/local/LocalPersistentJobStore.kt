package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.jobs.JobSnapshot
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Small crash-safe snapshot store for background jobs.
 *
 * Running records survive process death as "running" on disk; HarnessJobManager converts them to
 * "interrupted" on restore so only explicitly resumable, safe-to-replay jobs can restart.
 */
internal class LocalPersistentJobStore(
    private val file: File,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    private val writeMutex = Mutex()
    private val pending = AtomicReference<List<JobSnapshot>>(emptyList())

    fun read(): List<JobSnapshot> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { raw ->
            val item = raw.jsonObject
            val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val label = item["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val status = item["status"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            JobSnapshot(
                id = id,
                label = label,
                status = status,
                output = item["output"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                resumeKind = item["resume_kind"]?.jsonPrimitive?.contentOrNull,
                resumePayload = item["resume_payload"]?.jsonPrimitive?.contentOrNull,
                updatedAt = item["updated_at"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }.getOrDefault(emptyList())

    fun writeAsync(snapshots: List<JobSnapshot>) {
        pending.set(snapshots.takeLast(MAX_RECORDS))
        scope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                writeNow(pending.get())
            }
        }
    }

    private fun writeNow(snapshots: List<JobSnapshot>) {
        file.parentFile?.mkdirs()
        val payload = buildJsonArray {
            snapshots.forEach { snapshot ->
                add(buildJsonObject {
                    put("id", snapshot.id)
                    put("label", snapshot.label)
                    put("status", snapshot.status)
                    put("output", snapshot.output)
                    snapshot.resumeKind?.let { put("resume_kind", it) }
                    snapshot.resumePayload?.let { put("resume_payload", it) }
                    put("updated_at", snapshot.updatedAt)
                })
            }
        }.toString()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(file)) {
            file.writeText(payload)
            temp.delete()
        }
    }

    private companion object {
        const val MAX_RECORDS = 64
    }
}
