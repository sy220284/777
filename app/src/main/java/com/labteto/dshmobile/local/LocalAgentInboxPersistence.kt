package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
        decoded += QueuedAgentInput(
            content = content,
            memoryInput = memoryInput,
            modelMessage = modelMessage,
            id = id,
        )
    }
    return decoded
}

private fun encodeQueuedAgentInput(input: QueuedAgentInput): JsonObject = buildJsonObject {
    put("id", input.id)
    put("content", input.content)
    put("memory_input", input.memoryInput)
    input.modelMessage?.let { put("model_message", it) }
}

private fun stringValue(data: JsonObject, key: String): String? {
    val value = data[key] as? JsonPrimitive ?: return null
    if (!value.isString) return null
    return value.contentOrNull
}
