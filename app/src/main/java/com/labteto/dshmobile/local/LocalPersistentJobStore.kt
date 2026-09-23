package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.jobs.JobSnapshot
import java.io.File
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
 * Crash-safe snapshot store for background jobs.
 *
 * Writes are synchronous and atomic: once a persistent job API returns, its recovery metadata has
 * already reached app-private storage. Output is bounded separately from the in-memory job buffer
 * so frequent progress updates cannot turn the snapshot file into an unbounded write workload.
 */
internal class LocalPersistentJobStore(
    private val file: File,
    private val json: Json,
) {
    private val lock = Any()

    fun read(): List<JobSnapshot> = synchronized(lock) {
        runCatching {
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
    }

    fun write(snapshots: List<JobSnapshot>) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val payload = buildJsonArray {
            snapshots.takeLast(MAX_RECORDS).forEach { snapshot ->
                add(buildJsonObject {
                    put("id", snapshot.id)
                    put("label", snapshot.label)
                    put("status", snapshot.status)
                    put("output", snapshot.output.takeLast(MAX_PERSISTED_OUTPUT_CHARS))
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
        const val MAX_PERSISTED_OUTPUT_CHARS = 8_192
    }
}
