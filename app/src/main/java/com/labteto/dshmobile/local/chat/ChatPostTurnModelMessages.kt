package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val BASE_POST_TURN_SYSTEM_INSTRUCTION =
    "你是本机后台状态整理器。严格依据输入中的对话证据更新隐藏状态，只输出任务要求的结构化结果。"
private const val POST_TURN_SYSTEM_INSTRUCTION =
    BASE_POST_TURN_SYSTEM_INSTRUCTION + "\n" + CHAT_DIARY_SYSTEM_INSTRUCTION
private const val REPLY_SUGGESTION_SYSTEM_INSTRUCTION =
    "你是用户接话建议生成器。根据真实对话提供用户可直接发送的不同选择，不代替用户决定、不虚构经历、不扮演对方；只输出指定 JSON。"

internal fun chatPostTurnModelMessages(prompt: String): List<JsonObject> =
    backgroundChatModelMessages(prompt, POST_TURN_SYSTEM_INSTRUCTION)

internal fun chatReplySuggestionModelMessages(prompt: String): List<JsonObject> =
    backgroundChatModelMessages(prompt, REPLY_SUGGESTION_SYSTEM_INSTRUCTION)

private fun backgroundChatModelMessages(
    prompt: String,
    systemInstruction: String,
): List<JsonObject> {
    require(prompt.isNotBlank()) { "后台模型任务输入不能为空" }
    return listOf(
        buildJsonObject {
            put("role", "system")
            put("content", systemInstruction)
        },
        buildJsonObject {
            put("role", "user")
            put("content", prompt)
        },
    )
}
