package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.pendingForRequest
import com.labteto.dshmobile.local.chat.withLegacyFallback
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
): List<JsonObject> {
    require(recentMessages >= 2) { "recentMessages must be >= 2" }
    require(compactionBatch >= 2) { "compactionBatch must be >= 2" }

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

    val maximumHotMessages = recentMessages + compactionBatch
    if (dialogue.size <= maximumHotMessages) return history

    val overflow = dialogue.size - maximumHotMessages
    val summarizedCount = ((overflow + compactionBatch - 1) / compactionBatch) * compactionBatch
    val older = dialogue.take(summarizedCount.coerceAtMost(dialogue.size - recentMessages))
    val recent = dialogue.drop(older.size)
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
        appendLine("以下是按固定批次归并的较早用户表达与事件，只用于保持连续；除非用户追问，不主动复述：")
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
    context: ChatContextState = ChatContextState(),
): String = buildString {
    appendLine("【聊天连续性｜已发生】")
    val shared = context.withLegacyFallback(state)
    val scene = shared.scene
    if (
        scene.sceneTime.isNotBlank() ||
        scene.location.isNotBlank() ||
        scene.participants.isNotEmpty() ||
        scene.positions.isNotEmpty() ||
        scene.activeActions.isNotEmpty() ||
        scene.keyObjects.isNotEmpty()
    ) {
        appendLine(
            "当前场景：时间=${scene.sceneTime.ifBlank { "未知" }}｜地点=${scene.location.ifBlank { "未知" }}｜" +
                "人物=${scene.participants.joinToString("、").ifBlank { "未记录" }}",
        )
        if (scene.positions.isNotEmpty()) appendLine("人物位置：${scene.positions.joinToString("；")}")
        if (scene.activeActions.isNotEmpty()) appendLine("进行中：${scene.activeActions.joinToString("；")}")
        if (scene.keyObjects.isNotEmpty()) appendLine("关键物件：${scene.keyObjects.joinToString("、")}")
    }
    shared.continuity.recentEvents.takeLast(5).takeIf { it.isNotEmpty() }?.let {
        appendLine("近期关键事件：${it.joinToString("；").take(900)}")
    }
    shared.continuity.decisions.takeLast(4).takeIf { it.isNotEmpty() }?.let {
        appendLine("当前有效决定：${it.joinToString("；").take(720)}")
    }
    shared.continuity.unfinished.takeLast(4).takeIf { it.isNotEmpty() }?.let {
        appendLine("待续事项：${it.joinToString("；").take(720)}")
    }
    val pending = shared.pendingForRequest()
    if (pending.isNotEmpty()) {
        appendLine("尚未归并的最新事实：")
        pending.forEach { turn ->
            if (turn.userMessage.isNotBlank()) appendLine("- 用户：${normalizeChatContinuityText(turn.userMessage).take(320)}")
            if (turn.assistantMessage.isNotBlank()) appendLine("- 角色：${normalizeChatContinuityText(turn.assistantMessage).take(360)}")
        }
    }
    state.dynamics.sharedMoments.takeLast(6).takeIf { it.isNotEmpty() }?.let { moments ->
        appendLine("共同经历：${moments.joinToString("；").take(1_000)}")
    }
    val recentUserEvents = messages.asSequence()
        .filter { it.role == "user" }
        .map { normalizeChatContinuityText(it.content) }
        .filter(String::isNotBlank)
        .toList()
        .takeLast(4)
    if (recentUserEvents.isNotEmpty()) {
        appendLine("近期用户表达与事件：")
        recentUserEvents.forEach { appendLine("- ${it.take(420)}") }
    }
    append("原始聊天仍是最终事实来源；本摘要只保留当前有效状态与待续线索。")
}.trim().take(3_500)

