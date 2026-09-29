package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextAssembler
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.pendingForRequest
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

private fun isChatContinuitySummary(message: JsonObject): Boolean {
    val role = message["role"]?.jsonPrimitive?.contentOrNull
    if (role != "user" && role != "system") return false
    val content = (message["content"] as? JsonPrimitive)?.contentOrNull ?: return false
    return "<compacted-summary>" in content || "<chat-continuity>" in content
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


internal fun buildChatContinuationHandoff(
    state: ChatCharacterState,
    messages: List<LocalHarnessMessage>,
    context: ChatContextState = ChatContextState(),
): String {
    val shared = context
    val sceneBlock = buildString {
        val scene = shared.scene
        if (scene.sceneTime.isNotBlank() || scene.location.isNotBlank()) {
            appendLine(
                "当前硬场景：时间=${scene.sceneTime.ifBlank { "未知" }}｜地点=${scene.location.ifBlank { "未知" }}",
            )
            append("人物位置、动作和物件以最近原始对话为准，不从旧场景快照继承。")
        }
    }.trim().take(500)

    val pendingBlock = buildString {
        val pending = shared.pendingForRequest(limit = 4)
        if (pending.isNotEmpty()) {
            appendLine("尚未归并的最新事实：")
            pending.forEach { turn ->
                if (turn.userMessage.isNotBlank()) {
                    appendLine("- 用户：${normalizeChatContinuityText(turn.userMessage).take(240)}")
                }
                if (turn.assistantMessage.isNotBlank()) {
                    appendLine("- 角色：${normalizeChatContinuityText(turn.assistantMessage).take(280)}")
                }
            }
        }
    }.trim().take(1_200)

    val recentUserBlock = buildString {
        val recentUserEvents = messages.asSequence()
            .filter { it.role == "user" }
            .map { normalizeChatContinuityText(it.content) }
            .filter(String::isNotBlank)
            .toList()
            .takeLast(4)
        if (recentUserEvents.isNotEmpty()) {
            appendLine("近期用户表达与事件：")
            recentUserEvents.forEach { appendLine("- ${it.take(360)}") }
        }
    }.trim().take(1_200)

    val continuityBlock = buildString {
        shared.continuity.recentEvents.takeLast(5).takeIf { it.isNotEmpty() }?.let {
            appendLine("近期关键事件：${it.joinToString("；").take(800)}")
        }
        shared.continuity.decisions.takeLast(4).takeIf { it.isNotEmpty() }?.let {
            appendLine("当前有效决定：${it.joinToString("；").take(640)}")
        }
        shared.continuity.unfinished.takeLast(4).takeIf { it.isNotEmpty() }?.let {
            appendLine("待续事项：${it.joinToString("；").take(640)}")
        }
        state.dynamics.sharedMoments.takeLast(6).takeIf { it.isNotEmpty() }?.let { moments ->
            append("共同经历：${moments.joinToString("；").take(700)}")
        }
    }.trim().take(1_000)

    val body = listOf(sceneBlock, pendingBlock, continuityBlock, recentUserBlock)
        .filter(String::isNotBlank)
        .joinToString("\n")
        .take(3_300)

    return buildString {
        appendLine("【聊天连续性】")
        if (body.isNotBlank()) appendLine(body)
        append("原始聊天优先；本摘要仅保留当前有效状态和待续线索。")
    }.trim()
}

