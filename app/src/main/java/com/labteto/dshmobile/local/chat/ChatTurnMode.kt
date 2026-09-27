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

    if (RESET_HINTS.any { text.contains(it) }) return ChatTurnMode.RESET
    if (SCENE_TRANSITION_HINTS.any { text.contains(it) }) return ChatTurnMode.SCENE_TRANSITION
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
    ChatTurnMode.CONTINUE_BEAT -> """
        【本轮承接模式｜继续当前节拍】
        用户是在要求继续刚才正在发生的互动。保持当前时间、地点、在场人物和正在进行的话题，不为“推进”凭空换场、跳时、加入新人物、重开旧事件或重新解释已经说过的内容。
        只推进当前互动的下一拍：补充新的反应、动作、信息或一句自然承接。
    """.trimIndent()
    ChatTurnMode.FOLLOW_UP -> """
        【本轮承接模式｜追问】
        直接承接用户对上一轮的追问，以最近对话为主；先回答追问，再自然继续。没有明确过渡时保持当前场景，不额外开启新剧情。
    """.trimIndent()
    ChatTurnMode.CALLBACK -> """
        【本轮承接模式｜回看旧事】
        用户在主动回看过去内容。只调用与当前问题直接相关的旧事实；过去状态不能覆盖当前时间、地点、关系和最新决定。
    """.trimIndent()
    ChatTurnMode.SCENE_TRANSITION -> """
        【本轮承接模式｜用户要求转场】
        用户明确要求移动、换场或推进时间。先完成可理解的过渡，再在新场景继续；不要跳过必要的移动或时间衔接。
    """.trimIndent()
    ChatTurnMode.RESET -> """
        【本轮承接模式｜结束当前节拍】
        用户明确结束或切开刚才的话题。停止续写上一节拍；已经结束的动作、话题和临时情绪不应继续粘连到新内容。
    """.trimIndent()
    ChatTurnMode.NORMAL -> ""
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

private val RESET_HINTS = listOf(
    "换个话题", "先不聊这个", "不聊这个", "别提这个", "别再提", "说正事", "算了",
    "停一下", "到此为止", "结束这个话题",
)
