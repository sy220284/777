package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Request-only chat window.
 *
 * Full transcript remains durable in Session Event. Generation gets the base system prompt, one
 * continuity checkpoint and the recent raw dialogue so older wording cannot dominate the next turn.
 */
internal fun boundedChatRequestHistory(
    history: List<JsonObject>,
    recentMessages: Int = 20,
): List<JsonObject> {
    require(recentMessages >= 2) { "recentMessages must be >= 2" }
    if (history.size <= recentMessages + 2) return history

    val leadingSystem = history.firstOrNull()?.takeIf {
        it["role"]?.jsonPrimitive?.contentOrNull == "system"
    }
    val body = if (leadingSystem == null) history else history.drop(1)
    val existingSummaryIndex = body.indexOfLast(::isChatContinuitySummary)
    val existingSummary = body.getOrNull(existingSummaryIndex)
    val dialogueSource = if (existingSummaryIndex >= 0) {
        body.drop(existingSummaryIndex + 1)
    } else {
        body
    }
    val dialogue = dialogueSource.asSequence()
        .filter { message ->
            val role = message["role"]?.jsonPrimitive?.contentOrNull
            role == "user" || role == "assistant"
        }
        .filterNot(::isChatContinuitySummary)
        .toList()

    val recent = dialogue.takeLast(recentMessages)
    val older = dialogue.dropLast(recent.size)
    val deltaContinuity = buildRequestOnlyContinuity(older)

    return buildList {
        leadingSystem?.let(::add)
        existingSummary?.let(::add)
        deltaContinuity?.let(::add)
        addAll(recent)
    }
}

private fun isChatContinuitySummary(message: JsonObject): Boolean {
    if (message["role"]?.jsonPrimitive?.contentOrNull != "user") return false
    val content = (message["content"] as? JsonPrimitive)?.contentOrNull ?: return false
    return "<compacted-summary>" in content || "<chat-continuity>" in content
}

private fun buildRequestOnlyContinuity(older: List<JsonObject>): JsonObject? {
    val userEvents = older.asSequence()
        .filter { it["role"]?.jsonPrimitive?.contentOrNull == "user" }
        .mapNotNull { (it["content"] as? JsonPrimitive)?.contentOrNull }
        .map(::normalizeChatContinuityText)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
        .takeLast(8)
    if (userEvents.isEmpty()) return null

    val summary = buildString {
        appendLine("<chat-continuity>")
        appendLine("以下是较早已发生的用户表达与事件，只用于保持连续；除非用户追问，不主动复述：")
        userEvents.forEach { appendLine("- ${it.take(500)}") }
        appendLine("角色旧回复措辞已省略。")
        append("</chat-continuity>")
    }
    return buildJsonObject {
        put("role", "user")
        put("content", summary)
    }
}

private fun normalizeChatContinuityText(text: String): String =
    text.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()


internal fun buildChatContinuationHandoff(
    state: ChatCharacterState,
    messages: List<LocalHarnessMessage>,
): String = buildString {
    appendLine("【聊天连续性｜已发生】")
    state.dynamics.sharedMoments.takeLast(6).takeIf { it.isNotEmpty() }?.let { moments ->
        appendLine("共同经历：${moments.joinToString("；").take(1_200)}")
    }
    val recentUserEvents = messages.asSequence()
        .filter { it.role == "user" }
        .map { normalizeChatContinuityText(it.content) }
        .filter(String::isNotBlank)
        .toList()
        .takeLast(6)
    if (recentUserEvents.isNotEmpty()) {
        appendLine("近期用户表达与事件：")
        recentUserEvents.forEach { appendLine("- ${it.take(500)}") }
    }
    append("角色旧回复原文省略；当前关系、情绪、目标和开放线索由实时状态提供。")
}.trim().take(3_500)
