package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal data class ParsedChatPostTurnPlan(
    val plan: ChatPostTurnPlan,
    val rawState: JsonObject?,
)

internal class ChatInteractionPlanParser(
    private val json: Json,
) {
    fun parseSuggestions(text: String): List<ChatReplySuggestion>? {
        val body = extractJsonObject(text) ?: return null
        val decoded = runCatching {
            json.decodeFromString(ChatReplySuggestionPlan.serializer(), body)
        }.getOrNull() ?: return null
        return sanitizeSuggestions(decoded.suggestions)
    }

    fun parsePlan(text: String): ParsedChatPostTurnPlan? {
        val body = extractJsonObject(text) ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val decoded = runCatching {
            json.decodeFromString(ChatPostTurnPlan.serializer(), body)
        }.getOrNull() ?: return null
        val rawState = root["state"]?.let { runCatching { it.jsonObject }.getOrNull() }
        return ParsedChatPostTurnPlan(
            plan = decoded.copy(suggestions = sanitizeSuggestions(decoded.suggestions)),
            rawState = rawState,
        )
    }

    private fun sanitizeSuggestions(
        suggestions: List<ChatReplySuggestion>,
    ): List<ChatReplySuggestion> =
        suggestions.asSequence()
            .map { suggestion ->
                val style = suggestion.style.trim().take(12)
                ChatReplySuggestion(
                    label = suggestion.label.trim().take(12),
                    text = suggestion.text.trim().take(320),
                    style = style,
                    bold = suggestion.bold || style == "放飞",
                )
            }
            .filter { it.label.isNotBlank() && it.text.isNotBlank() }
            .distinctBy { normalizeChatInteractionText(it.text) }
            .take(4)
            .toList()

    private fun extractJsonObject(text: String): String? {
        val trimmed = text.trim()
            .removePrefix("~~~json")
            .removePrefix("~~~")
            .removeSuffix("~~~")
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return trimmed.substring(start, end + 1)
    }


}
