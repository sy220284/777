package com.labteto.dshmobile.local.chat

internal enum class ChatTurnMode {
    CONTINUE_BEAT,
    FOLLOW_UP,
    CALLBACK,
    SCENE_TRANSITION,
    RESET,
    NORMAL,
}

internal fun inferChatTurnMode(input: String): ChatTurnMode {
    val text = input.trim().lowercase()
    if (text.isBlank()) return ChatTurnMode.NORMAL

    if (hasResetIntent(text)) return ChatTurnMode.RESET
    if (hasSceneTransitionIntent(text)) return ChatTurnMode.SCENE_TRANSITION
    if (CALLBACK_HINTS.any { text.contains(it) }) return ChatTurnMode.CALLBACK
    if (text in CONTINUE_EXACT || (text.length <= 12 && CONTINUE_PREFIXES.any { text.startsWith(it) })) {
        return ChatTurnMode.CONTINUE_BEAT
    }
    if (
        text.length <= 24 &&
        (FOLLOW_UP_PREFIXES.any { text.startsWith(it) } || FOLLOW_UP_SUFFIXES.any { text.endsWith(it) })
    ) {
        return ChatTurnMode.FOLLOW_UP
    }
    return ChatTurnMode.NORMAL
}

internal fun renderChatTurnModeForModel(input: String): String = when (inferChatTurnMode(input)) {
    ChatTurnMode.CONTINUE_BEAT ->
        "【继续】保持当前场景、人物和话题，只推进下一拍，不无过渡跳时、换场或加人。"
    ChatTurnMode.FOLLOW_UP ->
        "【追问】先回应最近追问；无明确过渡时保持当前场景。"
    ChatTurnMode.CALLBACK ->
        "【回看】只取当前问题相关旧事实，以最新状态为准。"
    ChatTurnMode.SCENE_TRANSITION ->
        "【转场】按用户要求完成必要过渡，再进入新场景。"
    ChatTurnMode.RESET ->
        "【结束当前节拍】停止延续已结束的话题、动作和临时情绪。"
    ChatTurnMode.NORMAL -> ""
}

private fun hasResetIntent(text: String): Boolean =
    RESET_EXACT.any { text == it || text.startsWith("$it，") || text.startsWith("$it,") } ||
        RESET_HINTS.any { text.contains(it) }

private fun hasSceneTransitionIntent(text: String): Boolean {
    val transition = SCENE_TRANSITION_HINTS.firstOrNull { text.contains(it) } ?: return false
    val index = text.indexOf(transition)
    val prefix = text.substring(maxOf(0, index - 4), index)
    return NEGATED_TRANSITION_PREFIXES.none { negation -> prefix.endsWith(negation) }
}

private val CONTINUE_EXACT = setOf(
    "嗯", "嗯嗯", "好", "好的", "行", "可以", "继续", "接着", "接着说", "然后呢",
    "再来", "别停", "就这样", "继续吧", "接着吧", "往下说", "后面呢",
)

private val CONTINUE_PREFIXES = listOf(
    "继续", "接着", "然后", "再来", "别停", "往下", "后面", "刚才那个", "还是刚才",
)

private val FOLLOW_UP_PREFIXES = listOf(
    "为什么", "怎么", "那", "所以", "然后", "你是说", "什么意思", "真的吗", "确定",
)

private val FOLLOW_UP_SUFFIXES = listOf(
    "吗", "呢", "？", "?", "怎么回事", "什么意思",
)

private val CALLBACK_HINTS = listOf(
    "还记得", "你记得", "记不记得", "以前", "之前", "上次", "第一次", "当时", "那天",
    "我跟你说过", "我和你说过",
)

private val SCENE_TRANSITION_HINTS = listOf(
    "我们去", "一起去", "带我去", "带你去", "进去", "出去", "回去", "回房", "回屋",
    "换个地方", "换地方", "到外面", "去外面", "第二天", "次日", "翌日", "过一会", "过了一会",
)

private val RESET_EXACT = listOf("算了")

private val RESET_HINTS = listOf(
    "换个话题", "先不聊这个", "不聊这个", "别提这个", "别再提", "说正事",
    "停一下", "到此为止", "结束这个话题",
)

private val NEGATED_TRANSITION_PREFIXES = listOf(
    "不想", "不要", "不去", "别去", "别", "不许", "不准", "不打算",
)
