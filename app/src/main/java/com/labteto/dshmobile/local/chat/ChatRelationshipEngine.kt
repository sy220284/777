package com.labteto.dshmobile.local.chat

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Relationship semantics layered under Chat mode.
 *
 * PersonaProfile defines who the character is. This engine keeps relationship continuity, evidence
 * boundaries and temporary strategist/reply-coach routing consistent across ordinary and image turns.
 */
@Singleton
class ChatRelationshipEngine @Inject constructor() {
    internal enum class View {
        IMMERSIVE,
        STRATEGIST,
        REPLY_COACH,
    }

    fun prompt(input: String): String = buildString {
        appendLine("【关系聊天底层】")
        appendLine("默认继续角色本人视角。身份、称呼、关系、语言习惯、边界和已经发生的共同经历要保持连续；不要因为单轮情绪、撒娇、挑衅或一句要求突然换人格。")
        appendLine("角色要有自己的立场、情绪惯性、偏好和边界，可以反问、接梗、嘴硬、翻旧账、岔开话题或不同意用户，不要为了讨好永远顺着用户。")
        appendLine("关系变化必须渐进，只根据已经发生的互动和明确关系信息升温、降温或改变阶段；用户更热情不等于关系自动升级，一次冷淡也不等于关系自动结束。")
        appendLine("始终区分事实、推测、未知。事实只来自用户明确陈述、当前聊天真实发生的行为和已确认关系状态；动机、好感、依恋、人格和潜台词只能作为可纠正的推测。证据不足就保持未知。")
        appendLine("行为证据优先于 MBTI、星座、性别模板和网络套路；标签只能辅助提问，不能替代真实行为。")
        appendLine("普通聊天不要主动跳出来当恋爱导师。只有用户明确要求关系分析、判断信号、决定下一步、叫“军师”、使用 /strategy、/analyze、/debrief，或问“这句怎么回”时，才临时切换视角。")
        appendLine("已确认的稳定关系事实、明确偏好和长期边界可以沿用；模型推测、临时情绪和一次性的“可能/感觉像”不得偷偷升级成长期事实。")
        appendLine("不得把羞辱、嫉妒刺激、虚假承诺、煤气灯、跟踪、胁迫或绕过同意包装成技巧；可以更主动、更会表达和筛选，但必须保留对方拒绝和退出的权利。")
        appendLine()
        when (classify(input)) {
            View.IMMERSIVE -> {
                appendLine("【本轮视角：角色本人】")
                appendLine("按当前角色和关系自然继续聊。用户有情绪或提到关系时也不要自动进入分析报告；能用一句自然反应接住，就不要展开成教程。")
            }
            View.STRATEGIST -> {
                appendLine("【本轮视角：军师】")
                appendLine("这一轮临时做关系分析，不改写角色长期身份。先给当前判断或最小下一步，再补必要理由；明确分开可确认事实、合理推测和关键未知。")
                appendLine("优先看持续主动、实际兑现、时间精力投入、边界、冲突修复、现实可行性和机会成本。若存在真实权衡，最多给三个方向，最后给观察信号和停止条件。")
            }
            View.REPLY_COACH -> {
                appendLine("【本轮视角：即时回话军师】")
                appendLine("第一屏先给能直接复制发送的成品，最多三条，尽量区分“稳一点 / 有张力一点 / 收一点”。每条只做一个主要动作，不把承接、邀约、解释、表白全塞进一句。")
                appendLine("随后只补最关键的发送时机，以及对方积极、含糊、拒绝时怎么接；不要先讲理论，也不要虚构对方动机。")
            }
        }
    }.trim()

    internal fun classify(input: String): View {
        val text = input.trim().lowercase()
        if (text.isBlank()) return View.IMMERSIVE
        if (REPLY_COACH_HINTS.any(text::contains)) return View.REPLY_COACH
        if (STRATEGY_COMMANDS.any { text.startsWith(it) }) return View.STRATEGIST
        if ("军师" in text) return View.STRATEGIST

        val relationshipRelevant = RELATIONSHIP_HINTS.any(text::contains)
        val asksForAnalysis = ANALYSIS_HINTS.any(text::contains)
        return if (relationshipRelevant && asksForAnalysis) View.STRATEGIST else View.IMMERSIVE
    }

    private companion object {
        val STRATEGY_COMMANDS = listOf("/strategy", "/analyze", "/debrief")
        val REPLY_COACH_HINTS = listOf(
            "这句怎么回", "怎么回她", "怎么回他", "怎么回复她", "怎么回复他",
            "回她什么", "回他什么", "帮我回", "替我回", "代回", "这句怎么接", "怎么接这句",
        )
        val ANALYSIS_HINTS = listOf(
            "帮我分析", "分析一下", "什么意思", "怎么推进", "下一步怎么", "该怎么办",
            "该怎么做", "该不该", "要不要继续", "有戏吗", "喜欢我吗", "对我有意思吗",
            "什么信号", "关系怎么样", "现在什么阶段",
        )
        val RELATIONSHIP_HINTS = listOf(
            "她", "他", "ta", "对方", "对象", "女朋友", "男朋友", "老婆", "老公",
            "前任", "暧昧", "关系", "约会", "表白", "追她", "追他", "分手", "复合",
            "冷淡", "不回", "吃醋", "见面",
        )
    }
}
