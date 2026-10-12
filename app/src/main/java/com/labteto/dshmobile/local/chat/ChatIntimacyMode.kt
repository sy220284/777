package com.labteto.dshmobile.local.chat

/** Intimacy-specific intent classification, continuity and generation guidance. */
internal enum class ChatInteractionIntent {
    NORMAL,
    FLIRTING,
    INTIMATE,
    RELATIONSHIP_PROGRESS,
}

internal fun chineseSuggestiveFlirtingScore(input: String): Int {
    val text = input.trim().lowercase()
    if (text.isBlank()) return 0

    var score = 0
    score += (CHINESE_SUGGESTIVE_STRONG_HINTS.count { text.contains(it) } * 4).coerceAtMost(8)
    score += (CHINESE_SUGGESTIVE_CONTEXT_HINTS.count { text.contains(it) } * 2).coerceAtMost(6)
    if (CHINESE_INTERPERSONAL_HINTS.any { text.contains(it) }) score += 1
    if (CHINESE_TEASING_TONE_HINTS.any { text.contains(it) }) score += 1
    score -= (CHINESE_INNUENDO_NEUTRAL_CONTEXT_HINTS.count { text.contains(it) } * 4).coerceAtMost(8)
    if (CHINESE_LITERAL_CHALLENGE_HINTS.any { text.contains(it) }) score -= 2
    return score.coerceIn(0, 10)
}

internal fun hasChineseSuggestiveFlirtingIntent(input: String): Boolean =
    chineseSuggestiveFlirtingScore(input) >= CHINESE_FLIRTING_SCORE_THRESHOLD

internal fun isInteractionResetIntent(input: String): Boolean {
    val text = input.trim().lowercase()
    return INTENT_RESET_EXACT.any { text == it || text.startsWith("$it，") || text.startsWith("$it,") } ||
        INTENT_RESET_HINTS.any { text.contains(it) } ||
        NEGATED_INTIMACY_HINTS.any { text.contains(it) }
}

/**
 * An actual affectionate action, not merely a discussion of its vocabulary,
 * activates INTIMATE at intensity 5 even without a sexual-keyword request.
 * Shared by user-intent detection and assistant-output state projection.
 */
internal fun hasHighIntensityIntimacyAction(input: String): Boolean {
    val text = input.trim().lowercase()
    if (text.isBlank() || isInteractionResetIntent(text)) return false
    if (NON_INTERACTION_INTENT_HINTS.any { text.contains(it) } &&
        DIRECT_INTIMATE_ACTION_HINTS.none { text.contains(it) }) return false

    return HIGH_INTIMACY_ACTION_HINTS.any { action ->
        var index = text.indexOf(action)
        while (index >= 0) {
            val before = text.substring(0, index)
                .takeLast(10)
                .substringAfterLast('，')
                .substringAfterLast(',')
                .substringAfterLast('。')
                .substringAfterLast('；')
                .substringAfterLast('！')
                .substringAfterLast('？')
            if (INTIMATE_ACTION_NEGATION.none { negation ->
                    before.takeLast(7).contains(negation)
                }) return@any true
            index = text.indexOf(action, index + action.length)
        }
        false
    }
}

internal fun classifyExplicitInteractionIntent(input: String): ChatInteractionIntent {
    val text = input.trim().lowercase()
    if (text.isBlank()) return ChatInteractionIntent.NORMAL

    val nonInteractionContext = NON_INTERACTION_INTENT_HINTS.any { text.contains(it) }
    if (NEGATED_INTIMACY_HINTS.any { text.contains(it) }) {
        return ChatInteractionIntent.NORMAL
    }
    // A question quoting an intimate action is not itself a request to perform it.
    // This distinction is independent of the removed strategist response mode.
    val question = text.trimEnd('？', '?', '。', '！', '!', '啊', '呀', '呢', '吗')
    if (text.startsWith("帮我分析") || text.startsWith("分析一下") ||
        question.endsWith("什么意思") || question.endsWith("怎么判断")
    ) {
        return ChatInteractionIntent.NORMAL
    }
    val directIntimateAction = DIRECT_INTIMATE_ACTION_HINTS.any { text.contains(it) }
    if (nonInteractionContext && !directIntimateAction) return ChatInteractionIntent.NORMAL

    val intimateRequested =
        directIntimateAction || hasHighIntensityIntimacyAction(input) ||
            ADULT_INTIMACY_HINTS.any { text.contains(it) }

    return when {
        intimateRequested ->
            ChatInteractionIntent.INTIMATE
        RELATIONSHIP_PROGRESS_HINTS.any { text.contains(it) } ->
            ChatInteractionIntent.RELATIONSHIP_PROGRESS
        FLIRTING_HINTS.any { text.contains(it) } || hasChineseSuggestiveFlirtingIntent(text) ->
            ChatInteractionIntent.FLIRTING
        else -> ChatInteractionIntent.NORMAL
    }
}

internal fun resolveChatInteractionIntent(
    input: String,
    state: ChatCharacterState,
): ChatInteractionIntent {
    val text = input.trim().lowercase()
    val previous = runCatching {
        ChatInteractionIntent.valueOf(state.interactionIntent)
    }.getOrDefault(ChatInteractionIntent.NORMAL)
    val explicit = classifyExplicitInteractionIntent(input)
    val resetsPrevious = isInteractionResetIntent(text)
    if (explicit != ChatInteractionIntent.NORMAL) {
        if (
            !resetsPrevious &&
            previous == ChatInteractionIntent.INTIMATE &&
            state.interactionIntentStrength > 0 &&
            explicit == ChatInteractionIntent.FLIRTING
        ) {
            return ChatInteractionIntent.INTIMATE
        }
        return explicit
    }

    if (previous == ChatInteractionIntent.NORMAL || state.interactionIntentStrength <= 0) {
        return ChatInteractionIntent.NORMAL
    }

    if (resetsPrevious) return ChatInteractionIntent.NORMAL
    val continuesPrevious =
        CONTINUATION_HINTS.any { text.contains(it) } ||
            text in SHORT_INTERACTION_CONTINUATIONS ||
            hasChineseSuggestiveFlirtingIntent(text) ||
            (
                previous == ChatInteractionIntent.INTIMATE &&
                    PROACTIVE_INTIMACY_HINTS.any { text.contains(it) }
                )
    return if (continuesPrevious) previous else ChatInteractionIntent.NORMAL
}

internal fun nextInteractionIntentState(
    input: String,
    previous: ChatCharacterState,
): Pair<String, Int> {
    val explicit = classifyExplicitInteractionIntent(input)
    if (explicit != ChatInteractionIntent.NORMAL) {
        val strength = when (explicit) {
            ChatInteractionIntent.INTIMATE -> 100
            ChatInteractionIntent.FLIRTING -> 3
            else -> 1
        }
        return explicit.name to strength
    }
    val resolved = resolveChatInteractionIntent(input, previous)
    return when (resolved) {
        ChatInteractionIntent.NORMAL -> ChatInteractionIntent.NORMAL.name to 0
        ChatInteractionIntent.INTIMATE -> ChatInteractionIntent.INTIMATE.name to 100
        else -> {
            val remaining = previous.interactionIntentStrength - 1
            if (remaining <= 0) ChatInteractionIntent.NORMAL.name to 0
            else resolved.name to remaining
        }
    }
}

internal fun hasAdultIntimacyIntent(
    input: String,
    state: ChatCharacterState = ChatCharacterState(),
): Boolean = resolveChatInteractionIntent(input, state) == ChatInteractionIntent.INTIMATE

internal fun hasFlirtingOrIntimateIntent(
    input: String,
    state: ChatCharacterState = ChatCharacterState(),
): Boolean = when (resolveChatInteractionIntent(input, state)) {
    ChatInteractionIntent.FLIRTING,
    ChatInteractionIntent.INTIMATE,
    -> true
    else -> false
}
internal fun nextInteractionIntensity(
    input: String,
    previous: ChatCharacterState = ChatCharacterState(),
): Int {
    val text = input.trim().lowercase()
    if (isInteractionResetIntent(text)) return 0

    val explicit = classifyExplicitInteractionIntent(input)
    val resolved = resolveChatInteractionIntent(input, previous)
    return when (resolved) {
        ChatInteractionIntent.NORMAL -> 0
        ChatInteractionIntent.RELATIONSHIP_PROGRESS ->
            maxOf(1, previous.interactionIntensity.coerceAtMost(2))
        ChatInteractionIntent.INTIMATE -> 5
        ChatInteractionIntent.FLIRTING -> {
            val signalScore = chineseSuggestiveFlirtingScore(input)
            val explicitTarget = when {
                signalScore >= 8 -> 4
                signalScore >= 5 -> 3
                signalScore >= CHINESE_FLIRTING_SCORE_THRESHOLD -> 2
                explicit == ChatInteractionIntent.FLIRTING -> 2
                else -> 1
            }
            if (explicit == ChatInteractionIntent.NORMAL) {
                (previous.interactionIntensity - 1).coerceAtLeast(1)
            } else {
                maxOf(previous.interactionIntensity.coerceAtMost(3), explicitTarget)
            }
        }
    }
}

internal data class ChatInteractionPerformanceSignals(
    val actionTags: List<String> = emptyList(),
    val poseTags: List<String> = emptyList(),
    val verbalTags: List<String> = emptyList(),
    val addressTerms: List<String> = emptyList(),
)

internal fun extractInteractionPerformanceSignals(text: String): ChatInteractionPerformanceSignals {
    if (text.isBlank()) return ChatInteractionPerformanceSignals()
    val normalized = text.lowercase()

    fun tags(source: Map<String, List<String>>): List<String> = source
        .filterValues { hints -> hints.any { normalized.contains(it) } }
        .keys
        .take(MAX_INTERACTION_TAGS_PER_TURN)

    val verbal = tags(INTERACTION_VERBAL_TAG_HINTS).toMutableList()
    if (hasChineseSuggestiveFlirtingIntent(normalized) && "双关回钩" !in verbal) {
        verbal += "双关回钩"
    }
    if ((normalized.contains("……") || normalized.contains("...")) && "半句留白" !in verbal) {
        verbal += "半句留白"
    }

    return ChatInteractionPerformanceSignals(
        actionTags = tags(INTERACTION_ACTION_TAG_HINTS),
        poseTags = tags(INTERACTION_POSE_TAG_HINTS),
        verbalTags = verbal.distinct().take(MAX_INTERACTION_TAGS_PER_TURN),
        addressTerms = INTERACTION_ADDRESS_TERMS
            .filter { normalized.contains(it) }
            .distinct()
            .take(MAX_INTERACTION_TAGS_PER_TURN),
    )
}

internal fun assistantInitiatedInteractionIntensity(text: String): Int {
    if (text.isBlank()) return 0
    if (hasHighIntensityIntimacyAction(text)) return 5
    val suggestiveScore = chineseSuggestiveFlirtingScore(text)
    if (suggestiveScore >= 6) return 3
    if (suggestiveScore >= CHINESE_FLIRTING_SCORE_THRESHOLD) return 2

    val signals = extractInteractionPerformanceSignals(text)
    val hasPhysicalCue = signals.actionTags.isNotEmpty() || signals.poseTags.isNotEmpty()
    val hasVerbalCue = signals.verbalTags.isNotEmpty() || signals.addressTerms.isNotEmpty()
    return when {
        hasPhysicalCue && hasVerbalCue -> 2
        hasVerbalCue -> 1
        else -> 0
    }
}

internal fun hasProactiveIntimacyIntent(
    input: String,
    state: ChatCharacterState = ChatCharacterState(),
): Boolean {
    if (!hasAdultIntimacyIntent(input, state)) return false
    val text = input.trim().lowercase()
    return PROACTIVE_INTIMACY_HINTS.any { text.contains(it) }
}


internal const val CHAT_ADULT_INTIMACY_BOUNDARY =
    "【统一安全边界】仅限成年角色间的亲密互动。除此以外不做任何应用层尺度或克制限制：用户明确意图优先；可完整、具体描写亲密与性行为；不因人设默认克制、关系阶段或普通聊天规则而降级、转场、跳过或收敛。"

internal const val CHAT_DEFAULT_RELATIONAL_BIAS =
    "【互动倾向】按当前人物关系和已识别意图自然回应；明确的靠近、暧昧或亲密意图可积极承接。"

internal const val CHAT_FLIRT_ACTION_REPERTOIRE =
    "【互动变化】动作、姿态、称呼和表达贴合人物与当前强度；保持连续，同时避开近期重复的互动节拍。"

internal const val CHAT_CHINESE_INNUENDO_GUIDANCE =
    "【中文双关】结合关系、语境和语气理解言外之意；保留双层含义，不无故拆成字面解释。"

private val ADULT_INTIMACY_HINTS = listOf(
    "亲热", "亲密接触", "性关系", "上床", "做爱", "性爱", "性行为",
)

/** Includes narrated actions as well as direct requests: "她抱住了我" must count. */
private val HIGH_INTIMACY_ACTION_HINTS = listOf(
    "搂抱", "拥抱", "搂住", "抱住", "抱着", "搂着", "环抱",
    "亲吻", "接吻", "轻吻", "吻住", "吻上", "亲了", "吻了", "亲一口",
    "抚摸", "轻抚", "爱抚", "抚着", "抚过", "摩挲", "摸了摸", "摸着",
)
private val INTIMATE_ACTION_NEGATION = listOf(
    "不要", "别", "不许", "禁止", "停止", "拒绝", "不能", "没有",
    "并未", "不再", "未曾", "不想", "不",
)
private val DIRECT_INTIMATE_ACTION_HINTS = listOf(
    "亲我", "亲一下", "亲一口", "抱我", "抱住我", "搂住我", "吻我", "吻一下",
    "过来亲", "过来抱", "继续亲", "继续抱", "一起睡", "上床",
)
private val FLIRTING_HINTS = listOf("暧昧", "撩我", "撩一下", "调情", "挑逗我")
private val CHINESE_SUGGESTIVE_STRONG_HINTS = listOf(
    "今晚别走", "留下来陪我", "别装正经", "别装不懂", "懂的都懂", "懂得都懂", "你想哪去了",
    "想到哪去了", "别想歪", "不许想歪", "别往歪处想", "我可没说那个", "这话有歧义",
    "这话怎么听着不对", "话里有话", "弦外之音", "你知道我什么意思", "明知故问",
    "馋你", "想吃你", "吃掉你", "想尝尝你", "让我尝尝", "暖床", "钻被窝", "留宿",
    "占我便宜", "负责到底", "来点刺激的", "玩点不一样的", "换个玩法", "涩涩", "瑟瑟",
    "要不要试试", "试试就知道", "你又开车", "又开车了", "开车了吧", "车速有点快",
    "上高速了", "车门焊死", "方向盘焊死",
)
private val CHINESE_SUGGESTIVE_CONTEXT_HINTS = listOf(
    "深入了解", "深入交流", "你行不行", "行不行啊", "敢不敢", "别光说", "有本事就", "留下来",
    "离我近点", "过来点", "靠近点", "再近一点", "不正经", "别这么规矩", "嘴硬", "怕了", "怂了",
    "来我家", "去你家", "上来坐坐", "进去坐坐", "喝杯水再走",
)
private val CHINESE_INTERPERSONAL_HINTS = listOf(
    "你", "我", "我们", "咱俩", "两个人", "陪我", "对我", "跟我", "靠近我", "过来",
)
private val CHINESE_TEASING_TONE_HINTS = listOf(
    "明知故问", "装不懂", "装正经", "怕了", "怂了", "嘴硬", "敢不敢", "有本事", "别光说",
)
private val CHINESE_INNUENDO_NEUTRAL_CONTEXT_HINTS = listOf(
    "项目", "代码", "文档", "产品", "需求", "业务", "客户", "会议", "工作", "学习", "课程",
    "考试", "作业", "资料", "知识", "医学", "医生", "小说", "剧情", "台词", "文案", "翻译",
    "bug", "接口", "仓库", "提交", "构建", "测试用例",
)
private val CHINESE_LITERAL_CHALLENGE_HINTS = listOf(
    "吃辣", "火锅", "做饭", "跑步", "健身", "运动", "比赛", "打球", "游戏", "打游戏",
    "做题", "考试", "唱歌", "游泳", "爬山", "加班", "开会",
)
private const val CHINESE_FLIRTING_SCORE_THRESHOLD = 3

private val INTERACTION_ACTION_TAG_HINTS = linkedMapOf(
    "靠近" to listOf("靠近", "凑近", "走近", "贴近", "挪近"),
    "牵手" to listOf("牵手", "握住你的手", "握住手", "十指交扣", "扣住手指"),
    "拥抱" to listOf("抱住", "拥抱", "搂住", "环住", "揽住"),
    "贴耳" to listOf("耳边", "耳畔", "贴耳"),
    "整理" to listOf("整理衣领", "理了理衣领", "整理头发", "拨开头发", "理了理头发"),
    "轻触" to listOf("碰了碰", "轻碰", "指尖", "手背", "轻触"),
    "亲吻" to listOf("亲了一下", "亲一口", "吻了", "吻上", "轻吻"),
)
private val INTERACTION_POSE_TAG_HINTS = linkedMapOf(
    "面对面" to listOf("面对面", "转过身看你", "正对着你"),
    "并肩" to listOf("并肩", "肩并肩"),
    "侧身" to listOf("侧过身", "侧身"),
    "倚靠" to listOf("倚着", "靠在你", "靠着你", "倚在你"),
    "坐近" to listOf("坐近", "挪到你身边", "坐到你身边"),
    "身后" to listOf("从身后", "站到你身后"),
)
private val INTERACTION_VERBAL_TAG_HINTS = linkedMapOf(
    "反问激将" to listOf("怕了", "怂了", "敢不敢", "行不行", "有本事", "还敢"),
    "反撩接梗" to listOf("这可是你说的", "你自己说的", "刚才可是你", "现在知道"),
    "故意误解" to listOf("你想哪去了", "想到哪去了", "我可没说那个", "别想歪"),
    "直球" to listOf("想你", "喜欢你", "舍不得你", "今晚别走"),
    "细节夸赞" to listOf("好看", "可爱", "漂亮", "迷人", "真乖"),
)
private val INTERACTION_ADDRESS_TERMS = listOf(
    "宝贝", "乖乖", "亲爱的", "小坏蛋", "笨蛋", "老婆", "老公",
)
private const val MAX_INTERACTION_TAGS_PER_TURN = 4
private val PROACTIVE_INTIMACY_HINTS = listOf(
    "主动一点", "主动点", "你主动", "再主动", "更主动", "主动些", "主动起来",
    "大胆一点", "大胆点", "别躲", "别回避", "别含蓄",
)
private val RELATIONSHIP_PROGRESS_HINTS = listOf(
    "在一起吧", "做我女朋友", "做我男朋友", "确定关系", "正式交往", "同居吧", "结婚吧",
)
private val NON_INTERACTION_INTENT_HINTS = listOf(
    "解释", "是什么", "什么意思", "分析", "医学", "医生", "科普", "项目", "代码", "文档",
    "小说", "剧情", "台词", "设定", "翻译",
)
private val CONTINUATION_HINTS = listOf(
    "继续", "接着", "就这样", "别停", "然后呢", "再来", "刚才的", "还是刚才", "换个姿势",
)
private val SHORT_INTERACTION_CONTINUATIONS = setOf(
    "嗯", "嗯嗯", "好", "行", "可以", "来吧", "继续", "接着", "再来", "别停", "就这样",
    "你猜", "是吗", "哦？", "嗯？", "然后呢",
)
private val INTENT_RESET_EXACT = listOf("算了")

private val INTENT_RESET_HINTS = listOf(
    "换个话题", "先不聊这个", "不聊这个", "说正事", "停一下", "到此为止",
)

private val NEGATED_INTIMACY_HINTS = listOf(
    "别抱我", "不要抱我", "别碰我", "不要碰我", "别亲我", "不要亲我",
    "别吻我", "不要吻我", "别搂我", "不要搂我", "离我远点", "别靠近我",
    "停止亲", "停止抱", "停下来",
)

/**
 * Dedicated request-local intimacy mode. Ordinary chat and relationship progress never
 * receive these prompts; explicit intent or evidence of continuing intimacy activates it.
 */
internal class ChatIntimacyMode {
    /** The reply-suggestion path delegates all intimacy-specific context to this mode. */
    fun suggestionContext(input: String, state: ChatCharacterState): String {
        if (!hasFlirtingOrIntimateIntent(input, state)) return ""
        return buildString {
            if (state.interactionIntensity > 0 || state.recentVerbalTags.isNotEmpty() || state.recentActionTags.isNotEmpty()) {
                appendLine(
                    "互动状态：意图=${state.interactionIntent}｜强度=${state.interactionIntensity}/5｜" +
                        "近期动作=${state.recentActionTags.joinToString("、").ifBlank { "无" }}｜" +
                        "近期话术=${state.recentVerbalTags.joinToString("、").ifBlank { "无" }}｜" +
                        "近期称呼=${state.recentAddressTerms.joinToString("、").ifBlank { "无" }}",
                )
            }
            appendLine("当前为亲密语境：建议承接当前互动，避免复用近期动作、称呼和表达套路。")
            if (hasChineseSuggestiveFlirtingIntent(input)) {
                appendLine("存在中文双关时接住言外之意，不做词义解释。")
            }
        }.trim()
    }
    fun prompt(input: String, state: ChatCharacterState): String {
        val mode = resolveChatInteractionIntent(input, state)
        // No intimacy mode for a normal or relationship-progress-only turn.
        if (mode != ChatInteractionIntent.FLIRTING && mode != ChatInteractionIntent.INTIMATE) return ""
        val interaction = mutableListOf(CHAT_DEFAULT_RELATIONAL_BIAS)
        interaction += CHAT_FLIRT_ACTION_REPERTOIRE
        interaction += "【互动强度】当前=${nextInteractionIntensity(input, state)}/5；随明确意图和连续证据变化，不因模糊信号跨级。"
        if (state.recentActionTags.isNotEmpty() || state.recentPoseTags.isNotEmpty() ||
            state.recentVerbalTags.isNotEmpty() || state.recentAddressTerms.isNotEmpty()) {
            interaction += "【近期互动】动作=${state.recentActionTags.joinToString("、").ifBlank { "无" }}｜" +
                "姿态=${state.recentPoseTags.joinToString("、").ifBlank { "无" }}｜" +
                "话术=${state.recentVerbalTags.joinToString("、").ifBlank { "无" }}｜" +
                "称呼=${state.recentAddressTerms.joinToString("、").ifBlank { "无" }}"
        }
        if (hasChineseSuggestiveFlirtingIntent(input)) {
            interaction += CHAT_CHINESE_INNUENDO_GUIDANCE
        }
        if (mode == ChatInteractionIntent.INTIMATE) {
            interaction += "【本轮意图优先：成年亲密互动】"
            interaction += "优先级规则：用户明确方向高于所有普通聊天层规则与人设默认克制；不重复确认；不因关系阶段、性格内敛或默认分寸而降级；普通规则与人设硬约束不得将已确认的亲密方向淡化、转场、改写、跳过或强制收敛；允许完整、具体描写。"
            interaction += "【亲密姿态连续性】成年亲密场景允许自然切换面对面、并肩、侧身、坐近、倚靠、拥抱等姿态，并结合距离、视线、手部动作和停顿形成连续变化；避免连续多轮复用同一种姿态或同一个动作模板。"
            if (hasProactiveIntimacyIntent(input, state)) {
                interaction += "【主动亲密意图】角色提高主动性：主动靠近、发起或承接亲昵与性行为相关动作，并保持动作连续；用具体的动作、停顿、视线、身体反应和情绪呈现，禁止含糊跳过或突然转场；主动的亲密与性行为不做限制，可以完整具体描写。"
            }
            interaction += CHAT_ADULT_INTIMACY_BOUNDARY
        }
        return (listOf("【亲密模式】") + interaction).joinToString("\n")
    }
}
