package com.labteto.dshmobile.local.chat

internal fun StringBuilder.appendPostTurnPersonaContext(persona: PersonaProfile) {
    appendLine("人物=${persona.name}")
    persona.portrait.takeIf(String::isNotBlank)?.let { appendLine("人物底色：${it.take(700)}") }
    persona.lifeContext.takeIf(String::isNotBlank)?.let { appendLine("独立生活：${it.take(500)}") }
    if (persona.coreValues.isNotEmpty()) {
        appendLine("真正重要：${persona.coreValues.take(3).joinToString("；")}")
    }
    persona.coreTension.takeIf(String::isNotBlank)?.let { appendLine("长期拉扯：${it.take(320)}") }
    if (persona.stableTraits.isNotEmpty()) {
        appendLine("稳定部分：${persona.stableTraits.take(4).joinToString("；")}")
    }
    if (persona.mutableTraits.isNotEmpty()) {
        appendLine("可缓慢变化：${persona.mutableTraits.take(4).joinToString("；")}")
    }
    appendPersonaExpressionContext(persona)
}

internal fun StringBuilder.appendReplySuggestionPersonaContext(persona: PersonaProfile) {
    appendLine("对方人物=${persona.name}")
    persona.portrait.takeIf(String::isNotBlank)?.let { appendLine("人物理解：${it.take(420)}") }
    persona.initialUserImpression.takeIf(String::isNotBlank)?.let {
        appendLine("人物最初看用户的方式：${it.take(180)}")
    }
    appendPersonaExpressionContext(persona)
}
