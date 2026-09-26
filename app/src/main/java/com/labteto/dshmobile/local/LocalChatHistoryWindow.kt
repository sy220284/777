package com.labteto.dshmobile.local

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
    val existingSummary = body.lastOrNull(::isChatContinuitySummary)
    val dialogue = body.asSequence()
        .filter { message ->
            val role = message["role"]?.jsonPrimitive?.contentOrNull
            role == "user" || role == "assistant"
        }
        .filterNot(::isChatContinuitySummary)
        .toList()

    val recent = dialogue.takeLast(recentMessages)
    val older = dialogue.dropLast(recent.size)
    val continuity = existingSummary ?: buildRequestOnlyContinuity(older)

    return buildList {
        leadingSystem?.let(::add)
        continuity?.let(::add)
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
        .takeLast(8)
        .toList()
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
