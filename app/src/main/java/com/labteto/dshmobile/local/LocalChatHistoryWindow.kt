package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatContextAssembler
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
    compactionBatch: Int = 8,
    currentFacts: List<String> = emptyList(),
): List<JsonObject> {
    require(recentMessages >= 2) { "recentMessages must be >= 2" }
    require(compactionBatch >= 2) { "compactionBatch must be >= 2" }

    val leadingSystem = history.firstOrNull()?.takeIf {
        it["role"]?.jsonPrimitive?.contentOrNull == "system"
    }
    val body = if (leadingSystem == null) history else history.drop(1)
    val existingSummaryIndex = body.indexOfLast(::isChatContinuitySummary)
    val existingSummary = body.getOrNull(existingSummaryIndex)
        ?.let { summary -> pruneChatContinuitySummary(summary, currentFacts) }
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

    val maximumHotMessages = recentMessages + compactionBatch
    if (dialogue.size <= maximumHotMessages) {
        if (existingSummaryIndex < 0 || existingSummary == null) return history
        val rebuiltBody = body.toMutableList()
        rebuiltBody[existingSummaryIndex] = existingSummary
        return buildList {
            leadingSystem?.let(::add)
            addAll(rebuiltBody)
        }
    }

    val overflow = dialogue.size - maximumHotMessages
    val summarizedCount = ((overflow + compactionBatch - 1) / compactionBatch) * compactionBatch
    val older = dialogue.take(summarizedCount.coerceAtMost(dialogue.size - recentMessages))
    val recent = dialogue.drop(older.size)
    val deltaContinuity = buildRequestOnlyContinuity(older, currentFacts)

    return buildList {
        leadingSystem?.let(::add)
        existingSummary?.let(::add)
        deltaContinuity?.let(::add)
        addAll(recent)
    }
}

/**
 * Request-only group-chat window.
 *
 * Group chat keeps the complete public transcript durably, while each responder receives one
 * bounded continuity checkpoint plus the recent verbatim dialogue. Unlike single chat, older
 * assistant lines are retained in the checkpoint because speaker-to-speaker public events are part
 * of the shared scene, not just disposable role wording.
 */
internal fun boundedGroupChatRequestHistory(
    history: List<JsonObject>,
    recentMessages: Int = 28,
    compactionBatch: Int = 8,
    maxContinuityEvents: Int = 16,
): List<JsonObject> {
    require(recentMessages >= 4) { "recentMessages must be >= 4" }
    require(compactionBatch >= 2) { "compactionBatch must be >= 2" }
    require(maxContinuityEvents >= 4) { "maxContinuityEvents must be >= 4" }

    val leadingSystemCount = history.takeWhile { message ->
        message["role"]?.jsonPrimitive?.contentOrNull == "system"
    }.size
    val leadingSystem = history.take(leadingSystemCount)
    val body = history.drop(leadingSystemCount)
    val existingSummaryIndex = body.indexOfLast(::isGroupContinuitySummary)
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
        .filterNot(::isGroupContinuitySummary)
        .toList()

    val maximumHotMessages = recentMessages + compactionBatch
    if (dialogue.size <= maximumHotMessages) return history

    val overflow = dialogue.size - maximumHotMessages
    val summarizedCount = ((overflow + compactionBatch - 1) / compactionBatch) * compactionBatch
    val older = dialogue.take(summarizedCount.coerceAtMost(dialogue.size - recentMessages))
    val recent = dialogue.drop(older.size)
    val deltaContinuity = buildGroupRequestContinuity(older, maxContinuityEvents)

    return buildList {
        addAll(leadingSystem)
        existingSummary?.let(::add)
        deltaContinuity?.let(::add)
        addAll(recent)
    }
}

private fun isChatContinuitySummary(message: JsonObject): Boolean {
    val role = message["role"]?.jsonPrimitive?.contentOrNull
    if (role != "user" && role != "system") return false
    val content = (message["content"] as? JsonPrimitive)?.contentOrNull ?: return false
    return "<compacted-summary>" in content || "<chat-continuity>" in content
}

private fun isGroupContinuitySummary(message: JsonObject): Boolean {
    val role = message["role"]?.jsonPrimitive?.contentOrNull
    if (role != "user" && role != "system") return false
    val content = (message["content"] as? JsonPrimitive)?.contentOrNull ?: return false
    return "<compacted-summary>" in content || "<group-chat-continuity>" in content
}

private fun pruneChatContinuitySummary(
    message: JsonObject,
    currentFacts: List<String>,
): JsonObject {
    val content = (message["content"] as? JsonPrimitive)?.contentOrNull ?: return message
    val pruned = content.lineSequence().filter { raw ->
        val line = raw.trim()
        if (!line.startsWith("- ")) return@filter true
        val factLine = line.removePrefix("- ").trim()
        currentFacts.none { fact -> historicalFactSupersededByCurrent(factLine, fact) }
    }.joinToString("\n")
    return JsonObject(
        message +
            ("role" to JsonPrimitive("system")) +
            ("content" to JsonPrimitive(pruned)),
    )
}

private fun buildRequestOnlyContinuity(
    older: List<JsonObject>,
    currentFacts: List<String>,
): JsonObject? {
    val userEvents = older.asSequence()
        .filter { it["role"]?.jsonPrimitive?.contentOrNull == "user" }
        .mapNotNull { (it["content"] as? JsonPrimitive)?.contentOrNull }
        .map(::normalizeChatContinuityText)
        .filter(String::isNotBlank)
        .filter { event ->
            currentFacts.none { fact -> historicalFactSupersededByCurrent(event, fact) }
        }
        .distinct()
        .toList()
        .takeLast(8)
    if (userEvents.isEmpty()) return null

    val summary = buildString {
        appendLine("<chat-continuity>")
        appendLine("较早用户表达与事件，仅用于保持连续：")
        userEvents.forEach { appendLine("- ${it.take(500)}") }
        append("</chat-continuity>")
    }
    return buildJsonObject {
        put("role", "system")
        put("content", summary)
    }
}

private fun buildGroupRequestContinuity(
    older: List<JsonObject>,
    maxEvents: Int,
): JsonObject? {
    val events = older.asSequence()
        .mapNotNull { message ->
            val role = message["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val content = (message["content"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val normalized = normalizeChatContinuityText(content).take(420)
            if (normalized.isBlank()) return@mapNotNull null
            when (role) {
                "user" -> "用户：$normalized"
                "assistant" -> "群聊成员：$normalized"
                else -> null
            }
        }
        .distinct()
        .toList()
        .takeLast(maxEvents)
    if (events.isEmpty()) return null

    val summary = buildString {
        appendLine("<group-chat-continuity>")
        appendLine("较早群聊公开事件，仅用于保持共同场景连续；人物当前状态、关系、记忆与近期原文优先：")
        events.forEach { appendLine("- $it") }
        append("</group-chat-continuity>")
    }
    return buildJsonObject {
        put("role", "system")
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


private fun historicalFactSupersededByCurrent(
    historical: String,
    current: String,
): Boolean = ChatContextAssembler.factConflicts(historical, current)
