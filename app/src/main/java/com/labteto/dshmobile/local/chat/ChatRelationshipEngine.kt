package com.labteto.dshmobile.local.chat

import javax.inject.Inject
import javax.inject.Singleton

internal enum class ChatRelationshipView {
    IMMERSIVE,
    STRATEGIST,
    REPLY_COACH,
}

/**
 * Relationship cognition core for Chat mode.
 *
 * HeartFlow contributes persona continuity and gradual relationship change.
 * Goutoujunshi contributes evidence discipline, strategist routing and actionable reply coaching.
 * This layer keeps those semantics independent from any external Skill runtime.
 */
@Singleton
class ChatRelationshipEngine @Inject constructor() {

    fun classify(input: String): ChatRelationshipView {
        val text = input.trim().lowercase()
        if (text.isBlank()) return ChatRelationshipView.IMMERSIVE
        if (REPLY_COACH_HINTS.any(text::contains)) return ChatRelationshipView.REPLY_COACH
        if (STRATEGY_COMMANDS.any(text::startsWith)) return ChatRelationshipView.STRATEGIST
        if ("军师" in text) return ChatRelationshipView.STRATEGIST

        val relationshipRelevant = RELATIONSHIP_HINTS.any(text::contains)
        val asksForAnalysis = ANALYSIS_HINTS.any(text::contains)
        return if (relationshipRelevant && asksForAnalysis) {
            ChatRelationshipView.STRATEGIST
        } else {
            ChatRelationshipView.IMMERSIVE
        }
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
        appendLine("不使用操控、羞辱、嫉妒刺激、虚假承诺、服从测试、煤气灯、跟踪、胁迫或绕过同意来推进关系。")
        appendLine()

        appendDynamics(state.dynamics)
        appendUserPattern(state.userPattern)
        appendLine()

        when (classify(input)) {
            ChatRelationshipView.IMMERSIVE -> {
                appendLine("【本轮视角：角色本人】")
                appendLine("自然继续当前关系和未完话题。不要因为用户提到情绪、暧昧或关系，就自动跳成恋爱导师。")
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
    }.trim()

    private fun StringBuilder.appendDynamics(dynamics: RelationshipDynamics) {
        appendLine("【关系动力】")
        appendLine(
            "阶段=${dynamics.stage.name}；温度=${dynamics.warmth}/100；信任=${dynamics.trust}/100；" +
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
            "帮我分析", "分析一下", "什么意思", "怎么推进", "下一步怎么", "该怎么办",
            "该怎么做", "该不该", "要不要继续", "有戏吗", "喜欢我吗", "对我有意思吗",
            "什么信号", "关系怎么样", "现在什么阶段", "投入", "冷淡", "复合",
        )
        val RELATIONSHIP_HINTS = listOf(
            "她", "他", "ta", "对方", "对象", "女朋友", "男朋友", "老婆", "老公",
            "前任", "暧昧", "喜欢", "关系", "约会", "聊天", "消息", "回复", "表白",
            "追她", "追他", "分手", "复合", "冷淡", "不回", "吃醋", "见面", "吵架",
        )
    }
}
