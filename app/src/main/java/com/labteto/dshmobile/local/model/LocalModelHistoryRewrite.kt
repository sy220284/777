package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Remove only a completed, tool-free assistant reply; never cut through a tool-call batch. */
internal fun List<JsonObject>.withoutLastCompletedAssistantReply(): List<JsonObject> {
    val last = lastOrNull() ?: throw LocalModelException(
        "MODEL_HISTORY_INVALID",
        "当前历史没有可重新生成的助手回复",
        false,
    )
    val role = last["role"]?.jsonPrimitive?.contentOrNull
    val calls = last["tool_calls"] as? JsonArray
    if (role != "assistant" || !calls.isNullOrEmpty()) {
        throw LocalModelException(
            "MODEL_HISTORY_INVALID",
            "当前历史末尾仍处于工具调用批次，不能从中间重新生成",
            false,
        )
    }
    return dropLast(1)
}
