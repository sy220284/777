package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.estimateModelTokens

internal data class ChatLongTermMemoryBudget(
    val totalTokens: Int,
    val factTokens: Int,
    val diaryTokens: Int,
)

internal fun chatLongTermMemoryBudget(contextWindowTokens: Int?): ChatLongTermMemoryBudget {
    val total = when {
        contextWindowTokens != null && contextWindowTokens <= 8_192 -> 800
        contextWindowTokens != null && contextWindowTokens <= 16_384 -> 1_400
        contextWindowTokens != null && contextWindowTokens <= 32_768 -> 1_800
        else -> 2_200
    }
    return ChatLongTermMemoryBudget(
        totalTokens = total,
        factTokens = (total * 0.4).toInt().coerceAtLeast(240),
        diaryTokens = (total * 0.6).toInt().coerceAtLeast(360),
    )
}

internal fun shouldRecallDiary(query: String): Boolean {
    val text = query.trim().lowercase()
    if (text.isBlank()) return false
    if (ChatMemorySelector.shouldRecall(text)) return true
    return DIARY_RECALL_HINTS.any(text::contains)
}

internal fun takeWithinModelTokenBudget(text: String, maxTokens: Int): String {
    if (text.isBlank() || maxTokens <= 0) return ""
    if (estimateModelTokens(text) <= maxTokens) return text
    var low = 0
    var high = text.length
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        if (estimateModelTokens(text.take(mid)) <= maxTokens) low = mid else high = mid - 1
    }
    return text.take(low).trimEnd()
}

private val DIARY_RECALL_HINTS = listOf(
    "那天", "那次", "那时候", "后来", "经历", "发生过", "想起", "回忆", "记忆",
    "为什么会", "你当时", "你那时", "心里怎么想", "怎么想的", "怎么变成",
)
