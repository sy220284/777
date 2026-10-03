package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.harness.session.SessionRepairResult
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun loadModelHistoryReplayEvents(
    eventLog: LocalSessionEventLog,
    codec: ModelHistoryCheckpointCodec,
    legacyFallback: List<JsonObject>,
): List<LocalSessionEventLog.Event> {
    var beforeSequence = Long.MAX_VALUE
    while (true) {
        val checkpoint = eventLog.latest(
            ModelHistoryCheckpointCodec.EVENT_TYPE,
            beforeSequenceExclusive = beforeSequence,
        ) ?: break
        if (codec.decode(checkpoint.data) != null) {
            return eventLog.snapshotAfter(checkpoint.sequence - 1L)
        }
        beforeSequence = checkpoint.sequence
    }
    return if (legacyFallback.isNotEmpty()) emptyList() else eventLog.snapshot()
}

internal fun buildRecoveredToolResultMessages(
    modelHistory: List<JsonObject>,
    recovery: SessionRepairResult,
): List<JsonObject> {
    if (recovery.toolResults.isEmpty()) return emptyList()
    val seenCallIds = modelHistory.asSequence()
        .filter { message -> message["role"]?.jsonPrimitive?.contentOrNull == "tool" }
        .mapNotNull { message -> message["tool_call_id"]?.jsonPrimitive?.contentOrNull }
        .toMutableSet()
    return buildList {
        recovery.toolResults.forEach { recovered ->
            if (seenCallIds.add(recovered.callId)) {
                add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", recovered.callId)
                    put("content", recovered.modelContent)
                })
            }
        }
    }
}

internal fun migrateLegacySessionFiles(
    root: File,
    sessionsRoot: File,
    currentSessionId: String,
) {
    val legacy = File(root, "session.json")
    val destination = File(sessionsRoot, "$currentSessionId.json")
    if (!legacy.isFile || destination.exists()) return
    legacy.copyTo(destination, overwrite = false)
    File(root, "session.events.jsonl").takeIf(File::isFile)
        ?.copyTo(File(sessionsRoot, "$currentSessionId.events.jsonl"), overwrite = false)
}

internal fun seedLocalWorkspaceGuide(workspacePath: String) {
    val skill = File(workspacePath, ".dsh/skills/workspace-guide/SKILL.md")
    if (skill.exists()) return
    skill.parentFile?.mkdirs()
    skill.writeText(
        """
        # 工作区指南

        - 文件操作限当前工作区。
        - 修改前读取，修改后复核。
        - shell 使用 `/system/bin/sh` 和现有命令。
        """.trimIndent() + "\n",
    )
}

internal fun cleanupUnreferencedLocalImagesNow(
    sessionsRoot: File,
    currentSessionId: String,
    eventLogFor: (String) -> LocalSessionEventLog,
    modelHistory: List<JsonObject>,
    workspacePath: String,
    eventLog: LocalSessionEventLog,
) {
    val sessionIds = sessionsRoot.listFiles().orEmpty()
        .asSequence()
        .filter(File::isFile)
        .map(File::getName)
        .filter { name -> ".events.jsonl" in name }
        .map { name -> name.substringBefore(".events.jsonl") }
        .filter { id -> id.matches(Regex("[A-Za-z0-9._-]{1,128}")) }
        .plus(currentSessionId)
        .distinct()
        .toList()
    val references = mergeLocalImageAttachmentReferences(
        sessionIds.map { id ->
            eventLogFor(id).withEvents { events ->
                collectLocalImageAttachmentReferences(
                    events = events,
                    extraMessages = if (id == currentSessionId) modelHistory else emptyList(),
                )
            }
        },
    )
    val result = cleanupLocalImageAttachments(
        workspaceRoot = File(workspacePath),
        references = references,
    )
    if (result.deletedFiles > 0) {
        eventLog.append("attachment/gc", buildJsonObject {
            put("status", "completed")
            put("deleted_files", result.deletedFiles)
            put("deleted_bytes", result.deletedBytes)
            put("retained_image_bytes", result.retainedImageBytes)
        })
    }
}

