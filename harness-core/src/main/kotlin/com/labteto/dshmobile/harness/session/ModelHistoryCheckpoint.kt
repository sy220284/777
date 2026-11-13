package com.labteto.dshmobile.harness.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class ModelHistoryCheckpoint(
    val messages: List<JsonObject>,
    val reason: String?,
    val asOfSequence: Long?,
    val version: Int,
)

class ModelHistoryCheckpointCodec(
    private val version: Int = CURRENT_VERSION,
) {
    fun encode(
        messages: List<JsonObject>,
        reason: String,
        asOfSequence: Long? = null,
    ): JsonObject = buildJsonObject {
        put("version", version)
        put("reason", reason.take(80))
        asOfSequence?.takeIf { it >= -1L }?.let { put("as_of_sequence", it) }
        put("messages", JsonArray(messages))
    }

    fun decode(data: JsonObject): List<JsonObject>? =
        decodeCheckpoint(data)?.messages

    fun decodeCheckpoint(data: JsonObject): ModelHistoryCheckpoint? {
        val encodedVersion = data["version"]?.jsonPrimitive?.intOrNull ?: return null
        if (encodedVersion !in MIN_SUPPORTED_VERSION..version) return null
        val messages = data["messages"] as? JsonArray ?: return null
        val restored = messages.mapNotNull { element -> element as? JsonObject }
        if (restored.size != messages.size) return null
        if (restored.any { message ->
                message["role"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
            }) {
            return null
        }
        val asOfSequence = data["as_of_sequence"]?.jsonPrimitive?.longOrNull
        if (asOfSequence != null && asOfSequence < -1L) return null
        return ModelHistoryCheckpoint(
            messages = restored,
            reason = data["reason"]?.jsonPrimitive?.contentOrNull,
            asOfSequence = asOfSequence,
            version = encodedVersion,
        )
    }

    companion object {
        const val EVENT_TYPE = "local/model-history-checkpoint"
        const val CURRENT_VERSION = 2
        private const val MIN_SUPPORTED_VERSION = 1
    }
}
