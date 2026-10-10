package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.context.isTrustedContextCheckpointModelMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair

internal object LocalChatHistorySummaryStrategy {
    fun summarize(messages: List<JsonObject>, summaryLimit: Int): com.labteto.dshmobile.local.model.LocalRenderedHistorySummary {
        // Keep real user/character exchanges together. User-only fallback summaries lose
        // important replies and promises when the original assistant text is compacted.
        val excerptLength = (summaryLimit / 9).coerceIn(48, 260)
        val dialogue = messages.asReversed().asSequence()
            .filterNot { isTrustedContextCheckpointModelMessage(it) }
            .mapNotNull { message ->
                val role = message["role"].asText()
                if (role != "user" && role != "assistant") return@mapNotNull null
                messageText(message)?.let(::normalize)?.takeIf(String::isNotBlank)?.let { content ->
                    val speaker = if (role == "user") "用户" else "人物"
                    "$speaker：${truncateWithoutSplittingSurrogatePair(content, excerptLength)}"
                }
            }
            .distinct()
            .take(8)
            .toList().asReversed()
        val text = buildString {
            append("较早聊天连续性检查点，共折叠 ")
            append(messages.size)
            append(" 条模型消息，仅用于承接，不主动复述。")
            if (dialogue.isNotEmpty()) {
                append("\n\n较早用户表达与事件及人物当时回应：")
                dialogue.forEach { append("\n- ").append(it) }
            }
            append("\n\n旧人物回应仅说明当时说过的话；当前有效事实和决定优先。当前人设、关系、长期记忆和近期原始对话优先。")
        }
        val summary = truncateWithoutSplittingSurrogatePair(text, summaryLimit)
        return com.labteto.dshmobile.local.model.LocalRenderedHistorySummary(summary,
            "<compacted-summary>\n$summary\n</compacted-summary>")
    }

    private fun messageText(message: JsonObject): String? = when (val content = message["content"]) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.joinToString("\n") { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull.orEmpty()
                is JsonObject -> part["text"].asText().orEmpty()
                else -> ""
            }
        }.takeIf(String::isNotBlank)
        else -> null
    }

    private fun normalize(value: String): String =
        value.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun kotlinx.serialization.json.JsonElement?.asText(): String? =
        (this as? JsonPrimitive)?.contentOrNull

}
