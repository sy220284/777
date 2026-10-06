package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val LOCAL_MODEL_TOOL_CALLS_EVENT_KEY = "model_tool_calls"

internal fun JsonObject.withModelToolCallEventData(calls: List<LocalToolCall>): JsonObject =
    JsonObject(this + (LOCAL_MODEL_TOOL_CALLS_EVENT_KEY to modelToolCallEventData(calls)))

internal fun modelToolCallEventData(calls: List<LocalToolCall>): JsonArray = buildJsonArray {
    calls.forEach { call ->
        add(buildJsonObject {
            put("id", call.id)
            put("name", call.name)
        })
    }
}
