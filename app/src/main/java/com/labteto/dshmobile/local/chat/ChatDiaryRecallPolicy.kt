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
    if (text.isBlank() || text in LOW_INFORMATION_REPLIES) return false
    if (RESET_HINTS.any(text::contains)) return false
    return isExplicitDiaryRecall(text) || text.length >= 6
}

internal fun isExplicitDiaryRecall(query: String): Boolean {
    val text = query.trim().lowercase()
    return ChatMemorySelector.shouldRecall(text) || DIARY_RECALL_HINTS.any(text::contains)
}

internal fun diaryRecallUsageInstruction(groupAudience: Boolean): String =
    if (groupAudience) {
        "群聊边界：事件锚点可按披露级别自然引用；感受、心理活动、关系意义和余波属于当前角色的内在记忆，只用于塑造本人反应，不代表其他成员知情，也不要主动逐条公开。"
    } else {
        "这些日记属于当前角色自己的经历与内在记忆，只用于自然延续，不要为了展示记忆而逐条复述。"
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

private val LOW_INFORMATION_REPLIES = setOf("嗯", "嗯嗯", "好", "好的", "行", "可以", "继续", "接着", "哈哈", "哦", "啊")
private val RESET_HINTS = listOf("换个话题", "先不聊这个", "不聊这个", "别提这个", "别再提", "到此为止")
