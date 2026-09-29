package com.labteto.dshmobile.local.chat

private val PERSONA_INDIRECT_EXPRESSION_HINTS = listOf(
    "嘴硬", "心软", "服软", "害羞", "别扭", "口是心非", "傲娇",
    "不善于拒绝", "不太会拒绝", "不善拒绝", "含蓄", "克制",
)

internal fun StringBuilder.appendPersonaExpressionContext(persona: PersonaProfile) {
    if (persona.corrections.isNotEmpty()) {
        appendLine("用户纠正（最高优先）：${persona.corrections.takeLast(6).joinToString("；")}")
    }
    if (persona.hasIndirectExpressionSemantics()) {
        appendLine(
            "表达解释：角色存在含蓄、嘴硬、心软、害羞、别扭、服软或不善直接拒绝等特征；" +
                "状态判断必须同时看关系阶段、前后行为和持续参与证据，不以单个字面推辞覆盖既定人物模式；明确持续边界仍优先。",
        )
    }
}

internal fun composePersonaExpressionSemanticsPrompt(persona: PersonaProfile): String {
    if (!persona.hasIndirectExpressionSemantics()) return ""
    return """
        【角色表达解释】
        当前人设或用户纠正包含嘴硬、心软、害羞、别扭、服软、不善直接拒绝等表达特征。表面推辞、逞强、害羞或嘴硬台词先按人物表达方式理解，并与关系阶段、前后行为以及主动靠近、承接、回撩、继续参与等连续证据一起判断；不得仅凭单个“不行”“不要”“别”等字面词把人物突然改写成程序化拒绝。
        只有清晰、持续、与当前情境一致的停止、退出或拒绝继续，才改变互动方向；明确边界始终优先。人物可以含蓄、服软或嘴硬，但状态和行为必须保持连续。
    """.trimIndent()
}

private fun PersonaProfile.hasIndirectExpressionSemantics(): Boolean {
    val expressionProfile = buildString {
        append(personality)
        append(' ')
        append(speechStyle)
        append(' ')
        append(behaviorPatterns.joinToString(" "))
        append(' ')
        append(corrections.joinToString(" "))
    }
    return PERSONA_INDIRECT_EXPRESSION_HINTS.any { expressionProfile.contains(it, ignoreCase = true) }
}
