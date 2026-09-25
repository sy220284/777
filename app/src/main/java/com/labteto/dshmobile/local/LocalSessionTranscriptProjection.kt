package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal data class LocalSessionTranscriptProjection(
    val messages: List<LocalHarnessMessage>,
    val projectedThroughSequence: Long,
)

internal fun transcriptProjectionReplayCursor(
    snapshot: LocalHarnessSession,
    persistedSnapshotExists: Boolean,
    legacyBaselineSequence: Long?,
): Long = snapshot.transcriptProjectedThroughSequence
    ?: when {
        !persistedSnapshotExists -> -1L
        legacyBaselineSequence != null -> legacyBaselineSequence
        else -> error("旧会话缺少对话投影迁移基线")
    }

internal fun encodeTranscriptMessages(messages: List<LocalHarnessMessage>): JsonArray =
    JsonArray(messages.map(::encodeTranscriptMessage))

private fun encodeTranscriptMessage(message: LocalHarnessMessage): JsonObject =
    JsonObject(
        buildMap {
            put("id", JsonPrimitive(message.id))
            put("role", JsonPrimitive(message.role))
            put("content", JsonPrimitive(message.content))
            put("created_at", JsonPrimitive(message.createdAt))
            message.toolName?.let { put("tool_name", JsonPrimitive(it)) }
            message.speakerId?.let { put("speaker_id", JsonPrimitive(it)) }
            message.speakerName?.let { put("speaker_name", JsonPrimitive(it)) }
        },
    )

internal fun assistantModelMessageFromEvent(data: JsonObject): JsonObject =
    (data["message"] as? JsonObject) ?: JsonObject(data - "transcript" - "replaces")

internal fun projectSessionTranscriptTail(
    snapshotMessages: List<LocalHarnessMessage>,
    events: List<LocalSessionEventLog.Event>,
    sequenceExclusive: Long,
): LocalSessionTranscriptProjection {
    val messages = snapshotMessages.toMutableList()
    val knownIds = messages.mapTo(linkedSetOf(), LocalHarnessMessage::id)
    var projectedThrough = sequenceExclusive

    events
        .asSequence()
        .filter { event -> event.sequence > sequenceExclusive }
        .sortedBy { event -> event.sequence }
        .forEach { event ->
            if (event.type == "chat/active-transcript") {
                val decoded = decodeTranscriptMessages(event.data)
                if (decoded != null) {
                    messages.clear()
                    messages += decoded
                    knownIds.clear()
                    knownIds += decoded.map(LocalHarnessMessage::id)
                }
                projectedThrough = maxOf(projectedThrough, event.sequence)
                return@forEach
            }
            if (event.type == "assistant/message") {
                val replacedId = (event.data["replaces"] as? JsonPrimitive)?.contentOrNull
                if (replacedId != null) {
                    messages.removeAll { it.id == replacedId }
                    knownIds.remove(replacedId)
                }
            }
            val decoded = decodeTranscriptMessages(event.data)
            if (decoded != null) {
                decoded.forEach { message ->
                    if (knownIds.add(message.id)) messages += message
                }
            }
            projectedThrough = maxOf(projectedThrough, event.sequence)
        }

    return LocalSessionTranscriptProjection(
        messages = messages,
        projectedThroughSequence = projectedThrough,
    )
}

private fun decodeTranscriptMessages(data: JsonObject): List<LocalHarnessMessage>? {
    val encoded = data["transcript"] as? JsonArray ?: return emptyList()
    val decoded = mutableListOf<LocalHarnessMessage>()
    for (element in encoded) {
        val item = element as? JsonObject ?: return null
        val idValue = item["id"] as? JsonPrimitive ?: return null
        val roleValue = item["role"] as? JsonPrimitive ?: return null
        val contentValue = item["content"] as? JsonPrimitive ?: return null
        val createdAtValue = item["created_at"] as? JsonPrimitive ?: return null
        if (!idValue.isString || !roleValue.isString || !contentValue.isString) return null

        val id = idValue.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val role = roleValue.contentOrNull?.takeIf {
            it in setOf("user", "reasoning", "assistant", "progress", "tool", "system")
        } ?: return null
        val content = contentValue.contentOrNull ?: return null
        val createdAt = createdAtValue.longOrNull ?: return null
        fun optionalString(key: String): String? {
            val value = item[key] ?: return null
            if (value !is JsonPrimitive || !value.isString) return null
            return value.contentOrNull
        }
        val toolName = optionalString("tool_name")
        val speakerId = optionalString("speaker_id")
        val speakerName = optionalString("speaker_name")

        decoded += LocalHarnessMessage(
            id = id,
            role = role,
            content = content,
            toolName = toolName,
            speakerId = speakerId,
            speakerName = speakerName,
            createdAt = createdAt,
        )
    }
    return decoded
}
