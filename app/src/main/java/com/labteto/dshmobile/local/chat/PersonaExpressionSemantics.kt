package com.labteto.dshmobile.local.chat

/** Author-supplied corrections, kept as source material without a generated reaction recipe. */
internal fun StringBuilder.appendPersonaExpressionContext(persona: PersonaProfile) {
    if (persona.corrections.isNotEmpty()) {
        appendLine("用户纠正（最高优先）：${persona.corrections.takeLast(6).joinToString("；")}")
    }
}

