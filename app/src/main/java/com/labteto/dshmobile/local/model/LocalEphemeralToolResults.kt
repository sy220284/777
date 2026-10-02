package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.ToolResultRetention
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val LOCAL_TOOL_RESULT_RETENTION_KEY = "_dsh_tool_result_retention"
private const val LOCAL_EPHEMERAL_VALUE = "ephemeral"
internal const val EPHEMERAL_TOOL_RESULT_PLACEHOLDER =
    "[短生命周期工具结果未持久化；如仍需要当前画面或瞬时数据，请重新调用对应工具。]"

internal fun localToolHistoryMessage(
    callId: String,
    content: String,
    retention: ToolResultRetention,
): JsonObject = buildJsonObject {
    put("role", "tool")
    put("tool_call_id", callId)
    put("content", content)
    if (retention == ToolResultRetention.EPHEMERAL) {
        put(LOCAL_TOOL_RESULT_RETENTION_KEY, LOCAL_EPHEMERAL_VALUE)
    }
}

internal fun durableToolResultContent(
    content: String,
    retention: ToolResultRetention,
): String = if (retention == ToolResultRetention.EPHEMERAL) {
    EPHEMERAL_TOOL_RESULT_PLACEHOLDER
} else {
    content
}

internal fun durableModelHistorySnapshot(messages: List<JsonObject>): List<JsonObject> {
    var changed = false
    val projected = messages.map { message ->
        val ephemeral = message["role"]?.jsonPrimitive?.contentOrNull == "tool" &&
            message[LOCAL_TOOL_RESULT_RETENTION_KEY]?.jsonPrimitive?.contentOrNull == LOCAL_EPHEMERAL_VALUE
        if (!ephemeral) {
            message
        } else {
            changed = true
            buildJsonObject {
                message.forEach { (key, value) ->
                    if (key != "content" && key != LOCAL_TOOL_RESULT_RETENTION_KEY) put(key, value)
                }
                put("content", EPHEMERAL_TOOL_RESULT_PLACEHOLDER)
            }
        }
    }
    return if (changed) projected else messages
}
