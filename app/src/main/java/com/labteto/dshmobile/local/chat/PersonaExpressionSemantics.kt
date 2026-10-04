package com.labteto.dshmobile.local.chat

/**
 * Explicit user corrections remain hard constraints.
 *
 * Expression itself is no longer translated from words such as "嘴硬/傲娇/害羞" into a fixed
 * prompt recipe. The per-turn mode runtime decides how much of that tendency is visible.
 */
internal fun StringBuilder.appendPersonaExpressionContext(persona: PersonaProfile) {
    if (persona.corrections.isNotEmpty()) {
        appendLine("用户纠正（最高优先）：${persona.corrections.takeLast(6).joinToString("；")}")
    }
}

