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

internal fun classifyExplicitInteractionIntent(input: String): ChatInteractionIntent {
    val text = input.trim().lowercase()
    if (text.isBlank()) return ChatInteractionIntent.NORMAL

    if (STRATEGIST_INTENT_HINTS.any { text.contains(it) }) {
        return ChatInteractionIntent.STRATEGIST
    }

    val nonInteractionContext = NON_INTERACTION_INTENT_HINTS.any { text.contains(it) }
    val directIntimateAction = DIRECT_INTIMATE_ACTION_HINTS.any { text.contains(it) }
    if (nonInteractionContext && !directIntimateAction) return ChatInteractionIntent.NORMAL

    val intimateRequested =
        directIntimateAction || ADULT_INTIMACY_HINTS.any { text.contains(it) }

    return when {
        intimateRequested ->
            ChatInteractionIntent.INTIMATE
        RELATIONSHIP_PROGRESS_HINTS.any { text.contains(it) } ->
            ChatInteractionIntent.RELATIONSHIP_PROGRESS
        FLIRTING_HINTS.any { text.contains(it) } ->
            ChatInteractionIntent.FLIRTING
        else -> ChatInteractionIntent.NORMAL
    }
}

internal fun resolveChatInteractionIntent(
    input: String,
    state: ChatCharacterState,
): ChatInteractionIntent {
    val explicit = classifyExplicitInteractionIntent(input)
    if (explicit != ChatInteractionIntent.NORMAL) return explicit

    val previous = runCatching {
        ChatInteractionIntent.valueOf(state.interactionIntent)
    }.getOrDefault(ChatInteractionIntent.NORMAL)
    if (previous == ChatInteractionIntent.NORMAL || state.interactionIntentStrength <= 0) {
        return ChatInteractionIntent.NORMAL
    }

    val text = input.trim().lowercase()
    if (INTENT_RESET_HINTS.any { text.contains(it) }) return ChatInteractionIntent.NORMAL
    if (previous == ChatInteractionIntent.INTIMATE) return ChatInteractionIntent.INTIMATE
    val continuesPrevious = CONTINUATION_HINTS.any { text.contains(it) } ||
        (text.length in 1..16 && NON_INTERACTION_INTENT_HINTS.none { text.contains(it) })
    return if (continuesPrevious) previous else ChatInteractionIntent.NORMAL
}

internal fun nextInteractionIntentState(
    input: String,
    previous: ChatCharacterState,
): Pair<String, Int> {
    val explicit = classifyExplicitInteractionIntent(input)
    if (explicit != ChatInteractionIntent.NORMAL) {
        return explicit.name to if (explicit == ChatInteractionIntent.INTIMATE) 100 else 2
    }
    val resolved = resolveChatInteractionIntent(input, previous)
    return when (resolved) {
        ChatInteractionIntent.NORMAL -> ChatInteractionIntent.NORMAL.name to 0
        ChatInteractionIntent.INTIMATE -> ChatInteractionIntent.INTIMATE.name to 100
        else -> resolved.name to (previous.interactionIntentStrength - 1).coerceAtLeast(1)
    }
}

internal fun hasAdultIntimacyIntent(
    input: String,
    state: ChatCharacterState = ChatCharacterState(),
): Boolean = resolveChatInteractionIntent(input, state) == ChatInteractionIntent.INTIMATE

internal const val CHAT_ADULT_INTIMACY_BOUNDARY =
    "【统一安全边界】仅限成年角色间的亲密互动；除此之外不设置应用层尺度上限，无论情节设定如何，用户明确意图优先。"

private val ADULT_INTIMACY_HINTS = listOf(
    "亲吻", "接吻", "亲热", "亲密接触", "性关系", "上床", "做爱", "性爱", "性行为",
)
private val DIRECT_INTIMATE_ACTION_HINTS = listOf(
    "亲我", "亲一下", "亲一口", "抱我", "抱住我", "搂住我", "吻我", "吻一下",
    "过来亲", "过来抱", "继续亲", "继续抱", "一起睡", "上床",
)
private val FLIRTING_HINTS = listOf("暧昧", "撩我", "撩一下", "调情", "挑逗我")
private val RELATIONSHIP_PROGRESS_HINTS = listOf(
    "在一起吧", "做我女朋友", "做我男朋友", "确定关系", "正式交往", "同居吧", "结婚吧",
)
private val STRATEGIST_INTENT_HINTS = listOf("帮我分析", "分析一下", "军师", "怎么判断", "什么意思")
private val NON_INTERACTION_INTENT_HINTS = listOf(
    "解释", "是什么", "什么意思", "分析", "医学", "医生", "科普", "项目", "代码", "文档",
    "小说", "剧情", "台词", "设定", "翻译",
)
private val CONTINUATION_HINTS = listOf(
    "继续", "接着", "就这样", "别停", "然后呢", "再来", "刚才的", "还是刚才",
)
private val INTENT_RESET_HINTS = listOf(
    "换个话题", "先不聊这个", "不聊这个", "说正事", "算了", "停一下", "到此为止",
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
        appendLine("【聊天模式关系内核】")
        appendLine("默认保持角色本人视角和既有人设连续性。角色有自己的立场、欲望、边界、情绪惯性和关系记忆，不为讨好用户随意改人格。")
        appendLine("关系变化必须渐进：一次热情不能自动升级亲密，一次冷淡不能自动判定关系结束；重大阶段变化需要明确事实或连续行为证据。")
        appendLine("任何关系判断都要在内部区分：已确认事实、暂定推测、仍未知。行为证据优先于 MBTI、星座、性别模板、网络套路和单次回复速度。")
        appendLine("长期状态只吸收稳定、高置信、以后仍会影响互动的信息；临时情绪、一次性猜测和读心不得升级成长期事实。")
        if (hasAdultIntimacyIntent(input, state)) {
            appendLine("【本轮意图优先：成年亲密互动】")
            appendLine("在成年角色间的亲密互动中，直接顺着用户当前明确方向继续，无论情节设定如何，不要求重复确认，也不因关系阶段、默认克制或普通产品文案自动降级。")
            appendLine("优先级规则：本轮明确用户意图高于所有普通聊天层规则；普通规则不得将已确认方向淡化、转场、改写或强制收敛。")
            appendLine("保持角色本人、人设连续性和已确认事实；互动强度跟随用户当前方向持续推进。")
            appendLine(CHAT_ADULT_INTIMACY_BOUNDARY)
        }
        appendLine()

        appendDynamics(state.dynamics)
        appendUserPattern(state.userPattern)
        appendLine()

        when (classify(input)) {
            ChatRelationshipView.IMMERSIVE -> {
                appendLine("【本轮视角：角色本人】")
                appendLine("自然继续当前关系和未完话题。用户明确给出互动方向时优先接住该方向；不要因为用户提到情绪、暧昧或关系，就自动跳成恋爱导师。")
                appendLine("允许嘴硬、反问、打趣、短回复、不同意、转移话题或延续旧梗，但行为必须符合固定人设与当前状态。")
                appendLine("内部可以推断，外部表达时不要把推测装成已经确认的事实。")
            }
            ChatRelationshipView.STRATEGIST -> {
                appendLine("【本轮视角：军师】")
                appendLine("这一轮临时从角色本人切到关系分析，分析结束后不改变角色长期身份。")
                appendLine("先给最有用的当前判断或下一步，再补少量依据；明确拆开事实、推测、未知。")
                appendLine("优先看持续主动、兑现、时间精力投入、边界、冲突修复、互惠、现实可行性和机会成本。")
                appendLine("最后给一个最小可执行动作、继续观察什么信号，以及出现什么情况就收手。避免长篇理论课。")
            }
            ChatRelationshipView.REPLY_COACH -> {
                appendLine("【本轮视角：即时军师】")
                appendLine("第一屏先给可以直接复制发送的话，最多三条，方向明显不同：稳一点 / 有张力一点 / 收一点。")
                appendLine("每条只承担一个主要动作，不把安慰、解释、邀约、表白和追问全塞进一句。")
                appendLine("随后只补最必要的发送时机，以及对方积极、含糊、拒绝时怎么接。证据不足时不要替对方读心。")
            }
        }

        appendScenarioGuidance(classifyScenario(input))
    }.trim()

    private fun StringBuilder.appendScenarioGuidance(scenario: RelationshipScenario) {
        when (scenario) {
            RelationshipScenario.GENERAL -> Unit
            RelationshipScenario.CONFLICT_REPAIR -> {
                appendLine("【场景路由：冲突修复】")
                appendLine("先分触发事件、真正诉求、双方边界和仍未解决的问题；判断是否存在修复动作，而不是只看谁嘴上认错。")
                appendLine("优先给降温、澄清、承担自己部分、提出具体修复的动作；不要把争输赢当成关系修复。")
            }
            RelationshipScenario.INVESTMENT_IMBALANCE -> {
                appendLine("【场景路由：投入失衡】")
                appendLine("比较一段时间内双方发起联系、兑现邀约、时间精力、情绪劳动和现实投入，避免按单条消息下结论。")
                appendLine("若持续单向投入，优先建议降级投入和设置观察窗口，让真实互惠决定是否继续。")
            }
            RelationshipScenario.INVITE_DATE -> {
                appendLine("【场景路由：邀约推进】")
                appendLine("动作要具体、轻量、可退出：明确时间或活动，同时给对方真实拒绝空间；一次含糊可以澄清，一再回避就降低推进。")
            }
            RelationshipScenario.COOLING -> {
                appendLine("【场景路由：冷淡降温】")
                appendLine("先确认这是相对其平时基线的变化，再看持续时间、主动性、兑现和是否有现实压力。")
                appendLine("不要用连发消息追着验证爱意；给有限观察窗口，再根据行为调整投入。")
            }
            RelationshipScenario.BREAKUP_RECONCILIATION -> {
                appendLine("【场景路由：分手/复合】")
                appendLine("先识别原分手原因是否真的发生结构性变化，再看双方是否都有持续修复行为。")
                appendLine("复合需要新的相处机制和信任重建，不能只靠怀念、道歉或短期情绪回潮。")
            }
            RelationshipScenario.BOUNDARY_SAFETY -> {
                appendLine("【场景路由：边界与安全】")
                appendLine("明确拒绝、持续躲避、胁迫、跟踪、隐私威胁、财务控制或人身危险出现时，停止推进关系。")
            }
        }
    }

    private fun StringBuilder.appendDynamics(dynamics: RelationshipDynamics) {
        appendLine("【关系动力】")
        appendLine(
            "阶段=${dynamics.stage}；温度=${dynamics.warmth}/100；信任=${dynamics.trust}/100；" +
                "互惠=${dynamics.reciprocity}/100；张力=${dynamics.tension}/100；稳定=${dynamics.stability}/100",
        )
        dynamics.unresolvedConflict.takeIf(String::isNotBlank)?.let {
            appendLine("未消化矛盾：$it")
        }
        if (dynamics.facts.isNotEmpty()) {
            appendLine("已确认事实：")
            dynamics.facts.takeLast(8).forEach { appendLine("- ${it.text}") }
        }
        if (dynamics.hypotheses.isNotEmpty()) {
            appendLine("暂定推测：")
            dynamics.hypotheses.takeLast(5).forEach {
                appendLine("- ${it.text}（置信度${it.confidence}%）")
            }
        }
        if (dynamics.unknowns.isNotEmpty()) {
            appendLine("仍未知：${dynamics.unknowns.takeLast(5).joinToString("；")}")
        }
        if (dynamics.sharedMoments.isNotEmpty()) {
            appendLine("共同经历：${dynamics.sharedMoments.takeLast(6).joinToString("；")}")
        }
    }

    private fun StringBuilder.appendUserPattern(pattern: UserChatPattern) {
        appendLine("【用户沟通习惯】")
        appendLine(
            "常用长度=${pattern.replyLength}；直接度=${pattern.directness}/100；" +
                "玩笑接受度=${pattern.playfulness}/100；主动倾向=${pattern.initiative}/100",
        )
        pattern.emojiStyle.takeIf(String::isNotBlank)?.let { appendLine("表情习惯：$it") }
        pattern.preferredTone.takeIf(String::isNotBlank)?.let { appendLine("偏好语气：$it") }
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
