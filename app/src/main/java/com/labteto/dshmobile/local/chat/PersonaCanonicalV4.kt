package com.labteto.dshmobile.local.chat

/**
 * V4 boundary conversion for pre-V4 cards. Existing V4 identity and facts are authoritative;
 * stale legacy prose is never merged into them. Legacy-only cards migrate once into facts/lore.
 * Runtime-only boundaries, corrections and behavior tuning remain owned by their existing runtime.
 */
internal fun PersonaProfile.canonicalV4(): PersonaProfile {
    val alreadyV4 = coreIdentity.isNotBlank() || facts.isNotEmpty()
    val recoveredFacts = if (alreadyV4) emptyList() else buildList {
        fun add(category: String, value: String, id: String) {
            if (value.isBlank()) return
            add(CharacterFact(
                id = "legacy-v4-$id", category = category, content = value.trim(),
                provenance = CharacterFactProvenance.UNVERIFIED,
                sourceReference = "旧版人物卡",
            ))
        }
        fun lines(values: List<String>) = values.filter(String::isNotBlank).joinToString("；")
        add(CharacterFactCategories.BIOGRAPHY, portrait, "portrait")
        add(CharacterFactCategories.LIFE_GRAVITY, lifeContext, "life")
        add(CharacterFactCategories.SENSORY_SIGNATURE,
            lines(attentionBiases + perceptionBlindSpots + attentionKeywords), "attention")
        add(CharacterFactCategories.PREFERENCES_AND_HABITS, lines(quirks), "habits")
        add(CharacterFactCategories.LIMITS_AND_COSTS, lines(limitations), "limits")
        add(CharacterFactCategories.VALUES_AND_TRADEOFFS, lines(coreValues), "values")
        add(CharacterFactCategories.IDENTITY_GAP, coreTension, "tension")
        add(CharacterFactCategories.PERSONALITY, lines(stableTraits + mutableTraits), "personality")
        add(CharacterFactCategories.SUBJECTIVE_BELIEFS, initialUserImpression, "initial-impression")
        add(CharacterFactCategories.VOICE_STYLE, lines(voiceSamples), "voice")
    }
    val migratedWorld = if (!alreadyV4 && worldSetting.isNotBlank() &&
        loreEntries.none { it.title == "旧版世界背景" && it.content == worldSetting.trim() }
    ) listOf(PersonaLoreEntry(
        id = "legacy-v4-world", title = "旧版世界背景",
        content = worldSetting.trim(), alwaysOn = true,
    )) else emptyList()
    return copy(
        facts = if (alreadyV4) facts else facts + recoveredFacts,
        loreEntries = loreEntries + migratedWorld,
        portrait = "", lifeContext = "", attentionBiases = emptyList(),
        attentionKeywords = emptyList(), perceptionBlindSpots = emptyList(),
        quirks = emptyList(), limitations = emptyList(), coreValues = emptyList(),
        coreTension = "", stableTraits = emptyList(), mutableTraits = emptyList(),
        initialUserImpression = "", voiceSamples = emptyList(), worldSetting = "",
    )
}
