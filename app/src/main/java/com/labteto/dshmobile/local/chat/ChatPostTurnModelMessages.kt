package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val POST_TURN_SYSTEM_INSTRUCTION =
    "你是本机后台状态整理器。严格依据输入中的对话证据更新隐藏状态，只输出任务要求的结构化结果。"

internal fun chatPostTurnModelMessages(prompt: String): List<JsonObject> {
    require(prompt.isNotBlank()) { "后台状态整理输入不能为空" }
    return listOf(
        buildJsonObject {
            put("role", "system")
            put("content", POST_TURN_SYSTEM_INSTRUCTION)
        },
        buildJsonObject {
            put("role", "user")
            put("content", prompt)
        },
    )
}
