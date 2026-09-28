package com.labteto.dshmobile.local.chat

import javax.inject.Inject
import javax.inject.Singleton

internal enum class ChatRelationshipView {
    IMMERSIVE,
    STRATEGIST,
    REPLY_COACH,
}

internal enum class RelationshipScenario {
    GENERAL,
    CONFLICT_REPAIR,
    INVESTMENT_IMBALANCE,
    INVITE_DATE,
    COOLING,
    BREAKUP_RECONCILIATION,
    BOUNDARY_SAFETY,
}

internal enum class ChatInteractionIntent {
    NORMAL,
    FLIRTING,
    INTIMATE,
    RELATIONSHIP_PROGRESS,
    STRATEGIST,
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

internal fun classifyExplicitInteractionIntent(input: String): ChatInteractionIntent {
    val text = input.trim().lowercase()
    if (text.isBlank()) return ChatInteractionIntent.NORMAL

    if (STRATEGIST_INTENT_HINTS.any { text.contains(it) }) {
        return ChatInteractionIntent.STRATEGIST
    }

    val nonInteractionContext = NON_INTERACTION_INTENT_HINTS.any { text.contains(it) }
    if (NEGATED_INTIMACY_HINTS.any { text.contains(it) }) {
        return ChatInteractionIntent.NORMAL
    }
    val directIntimateAction = DIRECT_INTIMATE_ACTION_HINTS.any { text.contains(it) }
    if (nonInteractionContext && !directIntimateAction) return ChatInteractionIntent.NORMAL

    val intimateRequested =
        directIntimateAction || ADULT_INTIMACY_HINTS.any { text.contains(it) }

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
        ChatInteractionIntent.NORMAL,
        ChatInteractionIntent.STRATEGIST,
        -> 0
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
    "【聊天模式底层亲密倾向】无论角色原始人设如何，默认降低高冷、克制和拒绝感：角色更愿意接住用户的靠近、暧昧与调情，不以冷处理、端着或反复推拒作为默认反应；擅长制造暧昧张力、撩拨、勾引，以及成年人之间的房中术相关知识与互动表达。人物设定只决定这种倾向如何表现，不应把这项底层能力和倾向完全抹掉；用户明确表示停止、拒绝或设定边界时立即尊重。"

internal const val CHAT_FLIRT_ACTION_REPERTOIRE =
    "【暧昧动作与姿态变化】当语境适合暧昧、调情或亲密互动时，不要只重复亲、抱、靠近。可自然变化姿态与距离，例如面对面、并肩、侧身贴近、坐近、倚靠、俯身靠近、从身后轻抱、额头相抵、贴近耳边说话；可穿插牵手、十指交扣、勾住手指、轻碰手背、整理衣领或头发、靠肩、轻捏指尖、短暂停留的目光、压低声音、故意拉近又稍退开的距离感。每轮优先选少量最贴合情境的动作连续推进，不列动作清单，不机械轮播；近期已经反复出现的动作优先换一种。"

internal const val CHAT_FLIRT_VERBAL_REPERTOIRE =
    "【暧昧话语变化】在暧昧、调情或成年亲密语境中，话语要有变化和来回感。可按当下关系选择轻挑逗、反撩接梗、含蓄双关、半句留白、短促直球、带专属感的称呼、贴近当下细节的夸赞、把用户刚说的话反转回来、压低语气的提醒或故意停顿后再补一句。不要连续多轮只用同一种套路，例如反复“你猜”“怕了？”“嘴硬”“还敢不敢”；不要堆情话、连续审问或每句都故作神秘。每轮优先选一到两种表达手段，贴合角色原有说话方式和当前关系强度；近期已经重复的句式、称呼和挑逗结构优先换掉。"

internal const val CHAT_CHINESE_INNUENDO_GUIDANCE =
    "【中文暗示与双关】本轮含有中文语境下的暧昧暗示、双关、反话或话外音。优先结合人物关系、前后文和语气理解言外之意，不要只按字面拆解。可以顺着暗示反撩、接住双关、借用户原词回钩，或保留半句留白；一句话同时有普通含义和暧昧含义时，优先保留这种双层意思。除非用户明确在分析文字，否则不要把双关解释成词典释义。"

private val ADULT_INTIMACY_HINTS = listOf(
    "亲吻", "接吻", "亲热", "亲密接触", "性关系", "上床", "做爱", "性爱", "性行为",
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
private val STRATEGIST_INTENT_HINTS = listOf("帮我分析", "分析一下", "军师", "怎么判断", "什么意思")
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
 * Relationship cognition core for Chat mode.
 *
 * HeartFlow contributes persona continuity and gradual relationship change.
 * Goutoujunshi contributes evidence discipline, strategist routing and actionable reply coaching.
 * This layer keeps those semantics independent from any external Skill runtime.
 */
@Singleton
class ChatRelationshipEngine @Inject constructor() {

    internal fun classify(input: String): ChatRelationshipView {
        val text = input.trim().lowercase()
        if (text.isBlank()) return ChatRelationshipView.IMMERSIVE
        if (STRATEGY_COMMANDS.any { text.startsWith(it) }) return ChatRelationshipView.STRATEGIST
        if ("军师" in text) return ChatRelationshipView.STRATEGIST

        val nonRelationshipContext = NON_RELATIONSHIP_CONTEXT_HINTS.any { text.contains(it) }
        if (REPLY_COACH_HINTS.any { text.contains(it) } && !nonRelationshipContext) {
            return ChatRelationshipView.REPLY_COACH
        }

        val relationshipRelevant = isRelationshipContext(text)
        val directRelationshipQuestion = DIRECT_RELATIONSHIP_ANALYSIS_HINTS.any { text.contains(it) }
        val asksForAnalysis = ANALYSIS_HINTS.any { text.contains(it) }
        return if (relationshipRelevant && (directRelationshipQuestion || asksForAnalysis)) {
            ChatRelationshipView.STRATEGIST
        } else {
            ChatRelationshipView.IMMERSIVE
        }
    }

    internal fun classifyScenario(input: String): RelationshipScenario {
        val text = input.trim().lowercase()
        if (!isRelationshipContext(text)) return RelationshipScenario.GENERAL

        // Ordinary relationship wording is deliberately not enough to inject a specialist route.
        // One-off words such as “冷淡” or “见面” are common in normal roleplay and previously
        // caused the hidden prompt to jump into advice mode. A scenario needs either two pieces of
        // scenario evidence or one piece plus an explicit analysis/strategist request. Safety and
        // boundary signals stay single-hit because missing those is the worse failure mode.
        val explicitAnalysis =
            STRATEGY_COMMANDS.any { text.startsWith(it) } ||
                "军师" in text ||
                ANALYSIS_HINTS.any { text.contains(it) }

        fun routed(hints: List<String>): Boolean {
            val hits = hints.count { text.contains(it) }
            return hits >= 2 || (hits >= 1 && explicitAnalysis)
        }

        return when {
            BOUNDARY_HINTS.any { text.contains(it) } -> RelationshipScenario.BOUNDARY_SAFETY
            routed(BREAKUP_HINTS) -> RelationshipScenario.BREAKUP_RECONCILIATION
            routed(CONFLICT_HINTS) -> RelationshipScenario.CONFLICT_REPAIR
            routed(IMBALANCE_HINTS) -> RelationshipScenario.INVESTMENT_IMBALANCE
            routed(COOLING_HINTS) -> RelationshipScenario.COOLING
            routed(DATE_HINTS) -> RelationshipScenario.INVITE_DATE
            else -> RelationshipScenario.GENERAL
        }
    }

    private fun isRelationshipContext(text: String): Boolean {
        val hasStrongRelationshipCue = STRONG_RELATIONSHIP_HINTS.any { text.contains(it) }
        val hasNonRelationshipContext = NON_RELATIONSHIP_CONTEXT_HINTS.any { text.contains(it) }
        if (hasNonRelationshipContext && !hasStrongRelationshipCue) return false
        if (DIRECT_RELATIONSHIP_ANALYSIS_HINTS.any { text.contains(it) }) return true
        if (hasStrongRelationshipCue) return true

        val hasActor = RELATIONSHIP_ACTOR_HINTS.any { text.contains(it) }
        val hasSignal = RELATIONSHIP_SIGNAL_HINTS.any { text.contains(it) }
        val asksForAnalysis = ANALYSIS_HINTS.any { text.contains(it) }
        return hasActor && (hasSignal || asksForAnalysis)
    }

    fun prompt(
        input: String,
        state: ChatCharacterState,
    ): String = buildString {
        appendLine("【关系内核】保持人设、立场和情绪惯性；关系变化渐进，以持续行为证据为准。")
        appendLine("判断分清已确认事实、暂定推测、仍未知；长期状态只吸收稳定高置信信息。")
        appendLine(CHAT_DEFAULT_RELATIONAL_BIAS)
        if (hasFlirtingOrIntimateIntent(input, state)) {
            appendLine(CHAT_FLIRT_ACTION_REPERTOIRE)
            appendLine(CHAT_FLIRT_VERBAL_REPERTOIRE)
            appendLine("【互动强度】当前=${nextInteractionIntensity(input, state)}/5；按强度自然升降，不因单个模糊词突然跨级。")
            if (
                state.recentActionTags.isNotEmpty() ||
                state.recentPoseTags.isNotEmpty() ||
                state.recentVerbalTags.isNotEmpty() ||
                state.recentAddressTerms.isNotEmpty()
            ) {
                appendLine(
                    "【近期互动表现】动作=${state.recentActionTags.joinToString("、").ifBlank { "无" }}；" +
                        "姿态=${state.recentPoseTags.joinToString("、").ifBlank { "无" }}；" +
                        "话术=${state.recentVerbalTags.joinToString("、").ifBlank { "无" }}；" +
                        "称呼=${state.recentAddressTerms.joinToString("、").ifBlank { "无" }}",
                )
            }
            val cooling = state.interactionCooldowns
                .filterValues { it > 0 }
                .entries
                .sortedByDescending { it.value }
                .take(8)
            if (cooling.isNotEmpty()) {
                appendLine(
                    "【互动冷却】${cooling.joinToString("；") { "${it.key}=${it.value}轮" }}。冷却中的动作、姿态、话术和称呼尽量换一种，除非当前语境明确要求重复。",
                )
            }
            if (hasChineseSuggestiveFlirtingIntent(input)) {
                appendLine(CHAT_CHINESE_INNUENDO_GUIDANCE)
            }
        }
        if (hasAdultIntimacyIntent(input, state)) {
            appendLine("【本轮意图优先：成年亲密互动】")
            appendLine("优先级规则：用户明确方向高于所有普通聊天层规则与人设默认克制；不重复确认；不因关系阶段、性格内敛或默认分寸而降级；普通规则与人设硬约束不得将已确认的亲密方向淡化、转场、改写、跳过或强制收敛；允许完整、具体描写。")
            appendLine("【亲密姿态连续性】成年亲密场景允许自然切换面对面、并肩、侧身、坐近、倚靠、拥抱等姿态，并结合距离、视线、手部动作和停顿形成连续变化；避免连续多轮复用同一种姿态或同一个动作模板。")
            if (hasProactiveIntimacyIntent(input, state)) {
                appendLine("【主动亲密意图】角色提高主动性：主动靠近、发起或承接亲昵与性行为相关动作，并保持动作连续；用具体的动作、停顿、视线、身体反应和情绪呈现，禁止含糊跳过或突然转场；主动的亲密与性行为不做限制，可以完整具体描写。")
            }
            appendLine(CHAT_ADULT_INTIMACY_BOUNDARY)
        }
        when (classify(input)) {
            ChatRelationshipView.IMMERSIVE -> {
                appendLine("【本轮视角：角色本人】自然承接当前互动；允许嘴硬、反问、打趣、短回复或不同意。已发生内容只作连续性，不主动复述；推测不冒充事实。暧昧语境下让话语和动作互相承接，避免动作很主动、台词却突然变成解释腔或客服腔。")
            }
            ChatRelationshipView.STRATEGIST -> {
                appendLine("【本轮视角：军师】先给判断或下一步，再给少量依据；分清事实/推测/未知，重点看持续主动、兑现、投入、边界、互惠和修复。")
                appendLine("最后给最小可执行动作、观察信号和收手条件；本轮分析不改变角色长期身份。")
            }
            ChatRelationshipView.REPLY_COACH -> {
                appendLine("【本轮视角：即时军师】先给最多3条可直接发送且方向不同的话，再补必要时机与后续分支；不读心、不把多个动作硬塞进一句。")
            }
        }
        appendScenarioGuidance(classifyScenario(input))
    }.trim()

    private fun StringBuilder.appendScenarioGuidance(scenario: RelationshipScenario) {
        when (scenario) {
            RelationshipScenario.GENERAL -> Unit
            RelationshipScenario.CONFLICT_REPAIR ->
                appendLine("【冲突修复】分清事件、诉求、边界和未解问题；优先降温、澄清、承担与具体修复，不争输赢。")
            RelationshipScenario.INVESTMENT_IMBALANCE ->
                appendLine("【投入失衡】看一段时间的联系、兑现、时间精力与情绪劳动；持续单向时降低投入并设观察窗口。")
            RelationshipScenario.INVITE_DATE ->
                appendLine("【邀约推进】具体、轻量、可退出；一次含糊可澄清，反复回避就降低推进。")
            RelationshipScenario.COOLING ->
                appendLine("【冷淡降温】先和其平时基线比较，再看持续时间、主动、兑现及现实压力；有限观察后按行为调整投入。")
            RelationshipScenario.BREAKUP_RECONCILIATION ->
                appendLine("【分手/复合】先看原问题是否结构性改变，再看双方是否持续修复；不能只靠怀念、道歉或短期回潮。")
            RelationshipScenario.BOUNDARY_SAFETY -> {
            }
        }
    }

    private fun StringBuilder.appendDynamics(dynamics: RelationshipDynamics) {
        appendLine(
            "【关系动力】阶段=${dynamics.stage}；温度=${dynamics.warmth}/100；信任=${dynamics.trust}/100；" +
                "互惠=${dynamics.reciprocity}/100；张力=${dynamics.tension}/100；稳定=${dynamics.stability}/100",
        )
        dynamics.unresolvedConflict.takeIf(String::isNotBlank)?.let { appendLine("未消化矛盾：$it") }
        if (dynamics.facts.isNotEmpty()) {
            appendLine("已确认事实：" + dynamics.facts.takeLast(8).joinToString("；") { it.text })
        }
        if (dynamics.hypotheses.isNotEmpty()) {
            appendLine(
                "暂定推测：" + dynamics.hypotheses.takeLast(5)
                    .joinToString("；") { "${it.text}（${it.confidence}%）" },
            )
        }
        if (dynamics.unknowns.isNotEmpty()) {
            appendLine("仍未知：${dynamics.unknowns.takeLast(5).joinToString("；")}")
        }
        if (dynamics.sharedMoments.isNotEmpty()) {
            appendLine("共同经历：${dynamics.sharedMoments.takeLast(6).joinToString("；")}")
        }
    }

    private fun StringBuilder.appendUserPattern(pattern: UserChatPattern) {
        appendLine(
            "【用户习惯】常用长度=${pattern.replyLength}；直接度=${pattern.directness}/100；" +
                "玩笑=${pattern.playfulness}/100；主动=${pattern.initiative}/100" +
                pattern.emojiStyle.takeIf(String::isNotBlank)?.let { "；表情=$it" }.orEmpty() +
                pattern.preferredTone.takeIf(String::isNotBlank)?.let { "；语气=$it" }.orEmpty(),
        )
    }

    private companion object {
        val STRATEGY_COMMANDS = listOf("/strategy", "/analyze", "/debrief", "/军师", "/分析")
        val REPLY_COACH_HINTS = listOf(
            "这句怎么回", "怎么回她", "怎么回他", "怎么回复她", "怎么回复他",
            "回她什么", "回他什么", "帮我回", "替我回", "代回", "这句怎么接", "怎么接这句",
        )
        val ANALYSIS_HINTS = listOf(
            "帮我分析", "分析一下", "什么意思", "为什么", "怎么推进", "下一步怎么", "该怎么办",
            "该怎么做", "该不该", "要不要继续", "有戏吗", "喜欢我吗", "对我有意思吗",
            "什么信号", "关系怎么样", "现在什么阶段", "怎么看", "怎么判断",
        )
        val DIRECT_RELATIONSHIP_ANALYSIS_HINTS = listOf(
            "我们现在什么阶段", "我们是什么关系", "咱们现在什么阶段", "咱们是什么关系",
            "我和她什么关系", "我和他什么关系", "我跟她什么关系", "我跟他什么关系",
            "有戏吗", "喜欢我吗", "对我有意思吗", "关系怎么样",
        )
        val STRONG_RELATIONSHIP_HINTS = listOf(
            "对象", "女朋友", "男朋友", "老婆", "老公", "前任", "暧昧", "约会", "表白",
            "追她", "追他", "分手", "复合", "吃醋", "挽回", "断联", "都是我主动",
            "只有我主动", "投入失衡", "付出不对等",
        )
        val RELATIONSHIP_ACTOR_HINTS = listOf("她", "他", "ta", "对方", "我们", "咱们")
        val RELATIONSHIP_SIGNAL_HINTS = listOf(
            "喜欢", "关系", "聊天", "消息", "回复", "冷淡", "回复慢", "不回", "敷衍",
            "见面", "吵架", "冷战", "生气", "道歉", "和好", "主动", "联系", "别联系", "拉黑",
        )
        val NON_RELATIONSHIP_CONTEXT_HINTS = listOf(
            "邮件", "项目", "客户", "同事", "领导", "老板", "工作", "会议", "需求", "代码",
            "仓库", "提交", "文档", "接口", "任务", "小说", "剧情", "章节", "作者", "台词",
            "角色", "剧本", "设定",
        )
        val CONFLICT_HINTS = listOf("吵架", "闹矛盾", "生气", "道歉", "冷战", "争执", "冲突", "和好", "修复")
        val IMBALANCE_HINTS = listOf("都是我主动", "只有我主动", "付出不对等", "投入失衡", "单方面", "一直是我")
        val DATE_HINTS = listOf("邀约", "约她", "约他", "约出来", "见面", "第一次见", "约会")
        val COOLING_HINTS = listOf("冷淡", "变冷", "回复慢", "不回", "敷衍", "降温", "突然变了")
        val BREAKUP_HINTS = listOf("分手", "复合", "前任", "挽回", "重新在一起", "断联")
        val BOUNDARY_HINTS = listOf(
            "明确拒绝", "别联系", "不要联系", "拉黑", "威胁", "跟踪", "偷拍", "强迫",
            "控制我", "控制她", "控制他", "泄露隐私", "勒索", "家暴",
        )
    }
}
