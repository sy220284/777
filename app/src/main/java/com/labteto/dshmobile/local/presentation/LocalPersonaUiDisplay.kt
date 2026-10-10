package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.CharacterFactCategories
import com.labteto.dshmobile.local.chat.factText

/** Read-only character list display; Chat owns the V4 meaning of person facts. */
internal fun personaMemberSearchText(persona: PersonaProfile): String =
    buildString {
        appendLine(persona.name)
        appendLine(persona.coreIdentity)
        persona.facts.forEach { appendLine(it.content) }
    }

internal fun personaMemberDescription(persona: PersonaProfile): String =
    persona.coreIdentity.takeIf(String::isNotBlank)
        ?: persona.factText(CharacterFactCategories.BIOGRAPHY)
