package com.labteto.dshmobile.harness.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ModelHistoryCheckpointCodec(
    private val version: Int = CURRENT_VERSION,
) {
    fun encode(
        messages: List<JsonObject>,
        reason: String,
    ): JsonObject = buildJsonObject {
        put("version", version)
        put("reason", reason.take(80))
        put("messages", JsonArray(messages))
    }

    fun decode(data: JsonObject): List<JsonObject>? {
        val encodedVersion = data["version"]?.jsonPrimitive?.intOrNull ?: return null
        if (encodedVersion != version) return null
        val messages = data["messages"] as? JsonArray ?: return null
        val restored = messages.mapNotNull { element -> element as? JsonObject }
        if (restored.size != messages.size) return null
        if (restored.any { message ->
                message["role"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
            }) {
            return null
        }
        return restored
    }

    companion object {
        const val EVENT_TYPE = "local/model-history-checkpoint"
        const val CURRENT_VERSION = 1
    }
}
