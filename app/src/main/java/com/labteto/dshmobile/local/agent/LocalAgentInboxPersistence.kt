package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.agent.QueuedAgentInputKind
import com.labteto.dshmobile.harness.agent.AgentContinuationRef
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal const val LOCAL_AGENT_INBOX_EVENT_TYPE = "agent/inbox/spliced"
private const val LOCAL_AGENT_INBOX_STATE_VERSION = 1

internal fun encodeLocalAgentInboxEvent(
    action: String,
    pending: List<QueuedAgentInput>,
    affected: List<QueuedAgentInput> = emptyList(),
    modelMessages: List<JsonObject> = emptyList(),
    transcript: List<LocalHarnessMessage> = emptyList(),
): JsonObject = buildJsonObject {
    put("version", LOCAL_AGENT_INBOX_STATE_VERSION)
    put("action", action)
    put("queued_count", pending.size)
    put("pending", JsonArray(pending.map(::encodeQueuedAgentInput)))
    if (affected.isNotEmpty()) {
        put("queue_ids", JsonArray(affected.map { JsonPrimitive(it.id) }))
        put("affected", JsonArray(affected.map(::encodeQueuedAgentInput)))
    }
    if (modelMessages.isNotEmpty()) {
        put("model_messages", JsonArray(modelMessages))
    }
    if (transcript.isNotEmpty()) {
        put("transcript", encodeTranscriptMessages(transcript))
    }
}

internal fun decodeLocalAgentInboxPending(data: JsonObject): List<QueuedAgentInput>? {
    val version = (data["version"] as? JsonPrimitive)?.intOrNull ?: return null
    if (version != LOCAL_AGENT_INBOX_STATE_VERSION) return null
    val pending = data["pending"] as? JsonArray ?: return null
    if (pending.size > AgentInputQueue.DEFAULT_CAPACITY) return null

    val decoded = mutableListOf<QueuedAgentInput>()
    val ids = linkedSetOf<String>()
    for (element in pending) {
        val item = element as? JsonObject ?: return null
        val id = stringValue(item, "id")?.takeIf(String::isNotBlank) ?: return null
        if (!ids.add(id)) return null
        val content = stringValue(item, "content") ?: return null
        val memoryInput = stringValue(item, "memory_input") ?: content
        val modelMessage = item["model_message"] as? JsonObject
        val kind = when (stringValue(item, "kind")) {
            null, "USER" -> QueuedAgentInputKind.USER
            "AGENT_MESSAGE" -> QueuedAgentInputKind.AGENT_MESSAGE
            "WORK_CONTINUATION" -> QueuedAgentInputKind.WORK_CONTINUATION
            else -> return null
        }
        val metadata = item["continuation"]
        val continuation = if (metadata == null) null else {
            val ref = metadata as? JsonObject ?: return null
            val sourceRunId = stringValue(ref, "source_run_id")
                ?.takeIf(String::isNotBlank) ?: return null
            val sequence = (ref["checkpoint_sequence"] as? JsonPrimitive)?.longOrNull
                ?.takeIf { it >= 0 } ?: return null
            val failureCode = stringValue(ref, "failure_code") ?: return null
            val index = (ref["index"] as? JsonPrimitive)?.intOrNull
                ?.takeIf { it > 0 } ?: return null
            val goalId = stringValue(ref, "goal_id")
            val goalFingerprint = stringValue(ref, "goal_fingerprint")
            val revisionField = ref["goal_revision"]
            val goalRevision = (revisionField as? JsonPrimitive)?.longOrNull
            if (revisionField != null && (goalRevision == null || goalRevision < 0L)) return null
            val hasGoalVersion = goalRevision != null || goalFingerprint != null
            if ((goalId != null) != hasGoalVersion) return null
            if (goalId?.isBlank() == true || goalFingerprint?.isBlank() == true) return null
            AgentContinuationRef(sourceRunId, sequence, failureCode, index,
                stringValue(ref, "source_message_id"), goalId, goalFingerprint, goalRevision)
        }
        if ((kind == QueuedAgentInputKind.WORK_CONTINUATION) != (continuation != null)) return null
        val source = item["agent_source"] as? JsonObject
        if ((kind == QueuedAgentInputKind.AGENT_MESSAGE) != (source != null)) return null
        if (kind == QueuedAgentInputKind.AGENT_MESSAGE && memoryInput.isNotEmpty()) return null
        decoded += QueuedAgentInput(
            content = content,
            memoryInput = memoryInput,
            modelMessage = modelMessage,
            id = id,
            kind = kind,
            continuation = continuation,
            agentSource = source,
        )
    }
    return decoded
}

private fun encodeQueuedAgentInput(input: QueuedAgentInput): JsonObject = buildJsonObject {
    put("id", input.id)
    put("content", input.content)
    put("memory_input", input.memoryInput)
    put("kind", input.kind.name)
    input.agentSource?.let { put("agent_source", it) }
    input.continuation?.let { ref ->
        put("continuation", buildJsonObject {
            put("source_run_id", ref.sourceRunId)
            put("checkpoint_sequence", ref.checkpointSequence)
            put("failure_code", ref.failureCode)
            put("index", ref.index)
            ref.sourceMessageId?.let { put("source_message_id", it) }
            ref.goalId?.let { put("goal_id", it) }
            ref.goalFingerprint?.let { put("goal_fingerprint", it) }
            ref.goalRevision?.let { put("goal_revision", it) }
        })
    }
    input.modelMessage?.let { put("model_message", it) }
}

private fun stringValue(data: JsonObject, key: String): String? {
    val value = data[key] as? JsonPrimitive ?: return null
    if (!value.isString) return null
    return value.contentOrNull
}
