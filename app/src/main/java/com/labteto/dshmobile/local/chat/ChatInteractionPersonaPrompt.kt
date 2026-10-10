package com.labteto.dshmobile.local.chat

internal fun StringBuilder.appendPostTurnPersonaContext(persona: PersonaProfile) {
    appendLine("人物=${persona.name}")
    val identity = persona.coreIdentity.ifBlank { persona.portrait }
    identity.takeIf(String::isNotBlank)?.let { appendLine("核心身份：${it.take(550)}") }
    val relevant = listOf(
        CharacterFactCategories.PERSONALITY,
        CharacterFactCategories.VALUES_AND_TRADEOFFS,
        CharacterFactCategories.LIFE_GRAVITY,
        CharacterFactCategories.RELATIONSHIPS,
    )
    relevant.forEach { category ->
        persona.factText(category).takeIf(String::isNotBlank)
            ?.let { appendLine("$category：${it.take(280)}") }
    }
    appendPersonaExpressionContext(persona)
}

internal fun StringBuilder.appendReplySuggestionPersonaContext(persona: PersonaProfile) {
    appendLine("对方人物=${persona.name}")
    val identity = persona.coreIdentity.ifBlank { persona.portrait }
    identity.takeIf(String::isNotBlank)?.let { appendLine("人物身份：${it.take(420)}") }
    persona.factText(CharacterFactCategories.RELATIONSHIPS).takeIf(String::isNotBlank)
        ?.let { appendLine("既有关系：${it.take(240)}") }
    appendPersonaExpressionContext(persona)
}
