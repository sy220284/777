package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.jobs.JobSnapshot
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * Persistent job APIs synchronously write recovery metadata before the job is launched. Writes use
 * fsync + atomic replace when the filesystem supports it, and keep the previous valid snapshot as
 * a backup. A corrupt primary is restored from that backup instead of silently becoming an empty
 * job list.
 */
internal class LocalPersistentJobStore(
    private val file: File,
    private val json: Json,
) {
    private val lock = Any()
    private val backup = File(file.parentFile, "${file.name}.bak")

    fun read(): List<JobSnapshot> = synchronized(lock) {
        if (!file.isFile) {
            return@synchronized if (backup.isFile) readFrom(backup) else emptyList()
        }
        try {
            readFrom(file)
        } catch (primaryError: Exception) {
            if (!backup.isFile) throw primaryError
            val recovered = try {
                readFrom(backup)
            } catch (_: Exception) {
                throw primaryError
            }
            runCatching {
                File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}").also { corrupt ->
                    file.copyTo(corrupt, overwrite = true)
                }
            }
            atomicWrite(file, backup.readText())
            recovered
        }
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
                    put("inbox", buildJsonArray {
                        snapshot.inbox.takeLast(MAX_PERSISTED_INBOX_MESSAGES).forEach { message ->
                            add(kotlinx.serialization.json.JsonPrimitive(message.take(MAX_PERSISTED_INBOX_CHARS)))
                        }
                    })
                    put("updated_at", snapshot.updatedAt)
                })
            }
        }.toString()

        // Preserve the last known-good generation before replacing the primary.
        if (file.isFile) {
            val existing = file.readText()
            readFromText(existing)
            atomicWrite(backup, existing)
        }
        atomicWrite(file, payload)
        if (!backup.isFile) {
            atomicWrite(backup, payload)
        }
    }

    private fun readFrom(source: File): List<JobSnapshot> = readFromText(source.readText())

    private fun readFromText(text: String): List<JobSnapshot> =
        json.parseToJsonElement(text).jsonArray.mapNotNull { raw ->
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
                inbox = item["inbox"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.takeLast(MAX_PERSISTED_INBOX_MESSAGES)
                    .orEmpty(),
                updatedAt = item["updated_at"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private companion object {
        const val MAX_RECORDS = 64
        const val MAX_PERSISTED_OUTPUT_CHARS = 8_192
        const val MAX_PERSISTED_INBOX_MESSAGES = 32
        const val MAX_PERSISTED_INBOX_CHARS = 4_000
    }
}
