package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val LOCAL_MODEL_TOOL_CALLS_EVENT_KEY = "model_tool_calls"

internal fun modelToolCallEventData(calls: List<LocalToolCall>): JsonArray = buildJsonArray {
    calls.forEach { call ->
        add(buildJsonObject {
            put("id", call.id)
            put("name", call.name)
        })
    }
}

internal fun modelToolCallIdsFromEvent(data: kotlinx.serialization.json.JsonObject): List<String>? =
    (data[LOCAL_MODEL_TOOL_CALLS_EVENT_KEY] as? JsonArray)?.mapNotNull { raw ->
        (raw as? kotlinx.serialization.json.JsonObject)
            ?.get("id")
            ?.jsonPrimitive
            ?.contentOrNull
    }
