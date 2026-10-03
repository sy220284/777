package com.labteto.dshmobile.local.chat

private val PERSONA_INDIRECT_EXPRESSION_HINTS = listOf(
    "嘴硬", "心软", "服软", "害羞", "别扭", "口是心非", "傲娇",
    "不善于拒绝", "不太会拒绝", "不善拒绝",
)

internal fun StringBuilder.appendPersonaExpressionContext(persona: PersonaProfile) {
    if (persona.corrections.isNotEmpty()) {
        appendLine("用户纠正（最高优先）：${persona.corrections.takeLast(6).joinToString("；")}")
    }
    if (persona.hasIndirectExpressionSemantics()) {
        appendLine(
            "表达解释：角色存在嘴硬、心软、害羞、别扭、服软、口是心非或不善直接拒绝等特征；" +
                "状态判断必须同时看关系阶段、前后行为和持续参与证据；模糊或习惯性推辞不得单独触发关系降温、冲突或边界状态。" +
                "针对当前行为的清晰明确停止、退出或拒绝继续一次即生效，不要求重复。",
        )
    }
}

internal fun composePersonaExpressionSemanticsPrompt(persona: PersonaProfile): String {
    val corrections = persona.corrections.takeLast(6)
    val expression = persona.hasIndirectExpressionSemantics()
    if (corrections.isEmpty() && !expression) return ""
    return buildString {
        if (corrections.isNotEmpty()) {
            appendLine("【用户纠正｜最高优先】")
            appendLine(corrections.joinToString("；"))
        }
        if (expression) {
            appendLine("【角色表达解释】")
            appendLine(
                "当前人物或用户纠正包含嘴硬、心软、害羞、别扭、服软、口是心非、不善直接拒绝等表达特征。" +
                    "表面推辞、逞强、害羞或嘴硬台词要结合关系阶段、前后行为以及持续参与证据判断；" +
                    "不得仅凭脱离语境的单个“不行”“不要”“别”等模糊或习惯性推辞把人物突然改写成程序化拒绝。",
            )
            appendLine(
                "针对当前行为的清晰明确停止、退出或拒绝继续，即使只表达一次，也立即改变互动方向；" +
                    "无需等待重复确认。明确边界始终优先。人物可以含蓄、服软或嘴硬，但状态和行为必须保持连续。",
            )
        }
    }.trim()
}

private fun PersonaProfile.hasIndirectExpressionSemantics(): Boolean {
    val expressionProfile = buildString {
        append(portrait)
        append(' ')
        append(coreTension)
        append(' ')
        append(stableTraits.joinToString(" "))
        append(' ')
        append(mutableTraits.joinToString(" "))
        append(' ')
        append(voiceSamples.joinToString(" "))
        append(' ')
        append(corrections.joinToString(" "))
    }
    return PERSONA_INDIRECT_EXPRESSION_HINTS.any { expressionProfile.contains(it, ignoreCase = true) }
}
