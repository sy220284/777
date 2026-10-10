package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.estimateModelTokens

internal data class ChatLongTermMemoryBudget(
    val totalTokens: Int,
    val factTokens: Int,
    val diaryTokens: Int,
)

internal fun chatLongTermMemoryBudget(contextWindowTokens: Int?): ChatLongTermMemoryBudget {
    val total = when {
        contextWindowTokens == null -> 800
        contextWindowTokens <= 8_192 -> 800
        contextWindowTokens <= 16_384 -> 1_000
        contextWindowTokens <= 32_768 -> 1_150
        else -> 1_300
    }
    val facts = (total * 0.4).toInt().coerceIn(240, 500)
    return ChatLongTermMemoryBudget(
        totalTokens = total,
        factTokens = facts,
        diaryTokens = (total - facts).coerceIn(360, 800),
    )
}

internal fun shouldRecallDiary(query: String): Boolean {
    val text = query.trim().lowercase()
    if (text.isBlank() || text in LOW_INFORMATION_REPLIES) return false
    if (RESET_HINTS.any(text::contains)) return false
    if (isExplicitDiaryRecall(text) || ChatMemorySelector.shouldRecall(text)) return true
    if (text.length < 18) return false
    return SOFT_EXPERIENCE_HINTS.any(text::contains)
}

internal fun shouldSearchDiary(query: String): Boolean {
    val text = query.trim().lowercase()
    if (text.isBlank() || text in LOW_INFORMATION_REPLIES) return false
    if (RESET_HINTS.any(text::contains)) return false
    if (shouldRecallDiary(text)) return true
    val core = text.replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】|｜=_-]+"""), "")
    return core.length >= 6
}

internal fun diaryRecallItemLimit(query: String): Int =
    if (isExplicitDiaryRecall(query)) 3 else 1

internal fun isExplicitDiaryRecall(query: String): Boolean {
    val text = query.trim().lowercase()
    return EXPLICIT_DIARY_RECALL_HINTS.any(text::contains)
}

internal fun isHistoricalDiaryRecall(query: String): Boolean {
    val text = query.trim().lowercase()
    return HISTORICAL_RECALL_HINTS.any(text::contains)
}


/**
 * Security boundary for group-chat memory injection.
 *
 * PRIVATE and SHAREABLE are intentionally rejected here. Do not weaken this to "!= PRIVATE":
 * a direct-chat memory must have explicit public permission and be stored as PUBLIC before it may
 * enter a group prompt.
 */
internal fun canExposeDiaryToGroup(disclosure: ChatDiaryDisclosure): Boolean =
    disclosure == ChatDiaryDisclosure.PUBLIC

/** A PUBLIC event does not make the character's private feelings or reasoning public. */
internal fun renderRecalledChatDiary(entry: ChatDiaryEntry, groupAudience: Boolean): String =
    buildString {
        appendLine("- 事件：${entry.event}")
        if (!groupAudience) {
            entry.feeling.takeIf(String::isNotBlank)?.let { appendLine("  感受：$it") }
            entry.innerThought.takeIf(String::isNotBlank)?.let { appendLine("  心里：$it") }
            entry.relationshipMeaning.takeIf(String::isNotBlank)?.let {
                appendLine("  关系意义：$it")
            }
            entry.unresolvedEcho.takeIf(String::isNotBlank)?.let { appendLine("  余波：$it") }
        }
    }.trimEnd()

internal fun diaryRecallUsageInstruction(groupAudience: Boolean): String =
    if (groupAudience) {
        "群聊只能使用 disclosure=PUBLIC 且仍为当前有效版本的人物日记。PRIVATE 与 SHAREABLE 都不得进入群聊 Prompt，也不得以隐私余波、态度提示、隐藏摘要或其他旁路影响群聊回复。公开旧事只附带公开事件描述，人物未说出口的感受、内心想法、关系评价与余波不能进入群聊 Prompt。当前人物自己记得旧事，不代表其他成员此前知情；只有本轮真正说出口后，其他成员才获得这条公开信息。被后续取消、改期、结束或重新收紧权限的旧版本不得回潮。"
    } else {
        "这些日记属于当前角色自己的经历与内在记忆，只用于自然延续，不要为了展示记忆而逐条复述。当前有效版本优先；被后续取消、改期、结束或修正的旧版本不得当成当前状态回潮，除非用户明确追问以前/原来/最开始。"
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

private val EXPLICIT_DIARY_RECALL_HINTS = listOf(
    "那天", "那次", "那时候", "以前", "想起", "回忆", "记忆",
    "你当时", "你那时", "心里怎么想", "怎么想的", "怎么变成", "还记得", "上次", "之前说过",
)
private val SOFT_EXPERIENCE_HINTS = listOf(
    "以前", "之前", "后来", "最近一直", "还在", "那件事", "那个约定", "上回", "记得",
)
private val HISTORICAL_RECALL_HINTS = listOf(
    "原来", "原先", "最开始", "一开始", "以前那时候", "之前那时候", "当初", "最初", "曾经",
    "改之前", "取消之前", "分开之前", "以前是什么", "之前是什么", "当时还是",
)
private val LOW_INFORMATION_REPLIES = setOf(
    "嗯", "嗯嗯", "好", "好的", "行", "可以", "继续", "接着", "哈哈", "哦", "啊",
)
private val RESET_HINTS = listOf("换个话题", "先不聊这个", "不聊这个", "别提这个", "别再提", "到此为止")
