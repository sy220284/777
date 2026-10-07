package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.model.LOCAL_MODEL_TOOL_CALLS_EVENT_KEY
import com.labteto.dshmobile.local.tools.optionalString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
            if (message.blocks.isNotEmpty()) put("blocks", encodeTranscriptBlocks(message.blocks))
            put("created_at", JsonPrimitive(message.createdAt))
            message.toolName?.let { put("tool_name", JsonPrimitive(it)) }
            message.toolIsError?.let { put("tool_is_error", JsonPrimitive(it)) }
            message.toolErrorCode?.let { put("tool_error_code", JsonPrimitive(it)) }
            message.speakerId?.let { put("speaker_id", JsonPrimitive(it)) }
            message.speakerName?.let { put("speaker_name", JsonPrimitive(it)) }
            if (message.proactive) put("proactive", JsonPrimitive(true))
        },
    )

private fun encodeTranscriptBlocks(blocks: List<LocalMessageBlock>): JsonArray =
    JsonArray(blocks.map { block ->
        when (block) {
            is LocalMessageBlock.Text -> JsonObject(buildMap {
                put("kind", JsonPrimitive("text"))
                put("text", JsonPrimitive(block.text))
            })
            is LocalMessageBlock.Image -> JsonObject(buildMap {
                put("kind", JsonPrimitive("image"))
                put("path", JsonPrimitive(block.relativePath))
                put("media_type", JsonPrimitive(block.mediaType))
                put("name", JsonPrimitive(block.name))
                put("bytes", JsonPrimitive(block.bytes))
                block.attachmentId?.let { put("attachment_id", JsonPrimitive(it)) }
                block.width?.let { put("width", JsonPrimitive(it)) }
                block.height?.let { put("height", JsonPrimitive(it)) }
                put("source", JsonPrimitive(block.source.name.lowercase()))
            })
            is LocalMessageBlock.File -> JsonObject(buildMap {
                put("kind", JsonPrimitive("file"))
                put("path", JsonPrimitive(block.relativePath))
                put("media_type", JsonPrimitive(block.mediaType))
                put("name", JsonPrimitive(block.name))
                put("bytes", JsonPrimitive(block.bytes))
                block.attachmentId?.let { put("attachment_id", JsonPrimitive(it)) }
                put("source", JsonPrimitive(block.source.name.lowercase()))
            })
            is LocalMessageBlock.Unknown -> block.payload
        }
    })

private fun decodeTranscriptBlocks(value: JsonArray?): List<LocalMessageBlock>? {
    if (value == null) return emptyList()
    val blocks = mutableListOf<LocalMessageBlock>()
    for (element in value) {
        val item = element as? JsonObject ?: return null
        val kind = (item["kind"] as? JsonPrimitive)?.contentOrNull ?: return null
        fun string(key: String): String? =
            (item[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        fun long(key: String): Long? = (item[key] as? JsonPrimitive)?.longOrNull
        val source = string("source")
            ?.let { runCatching { LocalMessageMediaSource.valueOf(it.uppercase()) }.getOrNull() }
            ?: LocalMessageMediaSource.USER
        when (kind) {
            "text" -> blocks += LocalMessageBlock.Text(string("text") ?: return null)
            "image" -> blocks += LocalMessageBlock.Image(
                relativePath = string("path") ?: return null,
                mediaType = string("media_type") ?: return null,
                name = string("name") ?: return null,
                bytes = long("bytes") ?: return null,
                attachmentId = string("attachment_id"),
                width = long("width")?.toInt(),
                height = long("height")?.toInt(),
                source = source,
            )
            "file" -> blocks += LocalMessageBlock.File(
                relativePath = string("path") ?: return null,
                mediaType = string("media_type") ?: return null,
                name = string("name") ?: return null,
                bytes = long("bytes") ?: return null,
                attachmentId = string("attachment_id"),
                source = source,
            )
            else -> blocks += LocalMessageBlock.Unknown(
                kind = kind,
                payload = item,
            )
        }
    }
    return blocks
}

internal fun assistantModelMessageFromEvent(data: JsonObject): JsonObject =
    (data["message"] as? JsonObject) ?: JsonObject(
        data - "transcript" - "replaces" - LOCAL_MODEL_TOOL_CALLS_EVENT_KEY,
    )

internal fun projectSessionTranscriptTail(
    snapshotMessages: List<LocalHarnessMessage>,
    events: List<LocalSessionEventLog.Event>,
    sequenceExclusive: Long,
    maxMessages: Int? = null,
): LocalSessionTranscriptProjection {
    val messages = snapshotMessages
        .let { source -> maxMessages?.let(source::takeLast) ?: source }
        .toMutableList()
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
                    messages += maxMessages?.let(decoded::takeLast) ?: decoded
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
                maxMessages?.let { limit ->
                    while (messages.size > limit) {
                        knownIds.remove(messages.removeAt(0).id)
                    }
                }
            }
            projectedThrough = maxOf(projectedThrough, event.sequence)
        }

    return LocalSessionTranscriptProjection(
        messages = messages,
        projectedThroughSequence = projectedThrough,
    )
}

internal fun decodeTranscriptMessages(data: JsonObject): List<LocalHarnessMessage>? {
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
        val blocks = decodeTranscriptBlocks(item["blocks"] as? JsonArray) ?: return null
        val createdAt = createdAtValue.longOrNull ?: return null
        fun optionalString(key: String): String? {
            val value = item[key] ?: return null
            if (value !is JsonPrimitive || !value.isString) return null
            return value.contentOrNull
        }
        val toolName = optionalString("tool_name")
        val speakerId = optionalString("speaker_id")
        val speakerName = optionalString("speaker_name")
        val proactive = (item["proactive"] as? JsonPrimitive)?.booleanOrNull ?: false

        decoded += LocalHarnessMessage(
            id = id,
            role = role,
            content = content,
            toolName = toolName,
            toolIsError = (item["tool_is_error"] as? JsonPrimitive)?.booleanOrNull
                ?: (data["is_error"] as? JsonPrimitive)?.booleanOrNull,
            toolErrorCode = optionalString("tool_error_code")
                ?: (data["error_code"] as? JsonPrimitive)?.contentOrNull,
            speakerId = speakerId,
            speakerName = speakerName,
            createdAt = createdAt,
            proactive = proactive,
            blocks = blocks,
        )
    }
    return decoded
}
