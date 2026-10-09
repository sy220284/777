package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHistoryBudget
import com.labteto.dshmobile.local.context.LocalContextCheckpointKind
import com.labteto.dshmobile.local.context.buildTrustedContextCheckpointModelMessage
import com.labteto.dshmobile.local.context.isTrustedContextCheckpointModelMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair

internal object LocalChatHistorySummaryStrategy {
    fun summarize(messages: List<JsonObject>, summaryLimit: Int): com.labteto.dshmobile.local.model.LocalRenderedHistorySummary {
        val user = recentText(messages.filterNot { isTrustedContextCheckpointModelMessage(it) }, "user", 8, 1_200)
        val text = buildString {
            append("较早聊天连续性检查点，共折叠 ")
            append(messages.size)
            append(" 条模型消息，仅用于承接，不主动复述。")
            if (user.isNotEmpty()) {
                append("\n\n较早用户表达与事件：")
                user.forEach { append("\n- ").append(it) }
            }
            append("\n\n当前人设、关系、长期记忆和近期原始对话优先。")
        }
        val summary = truncateWithoutSplittingSurrogatePair(text, summaryLimit)
        return com.labteto.dshmobile.local.model.LocalRenderedHistorySummary(summary,
            "<compacted-summary>\n$summary\n</compacted-summary>")
    }
    private fun recentText(
        messages: List<JsonObject>,
        role: String,
        maxItems: Int,
        maxPerItem: Int,
    ): List<String> = messages.asReversed()
        .asSequence()
        .filter { it["role"].asText() == role }
        .mapNotNull(::messageText)
        .map(::normalize)
        .filter(String::isNotBlank)
        .distinct()
        .take(maxItems)
        .map { truncateWithoutSplittingSurrogatePair(it, maxPerItem) }
        .toList()
        .asReversed()

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
