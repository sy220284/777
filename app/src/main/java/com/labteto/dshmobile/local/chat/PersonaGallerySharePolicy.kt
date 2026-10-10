package com.labteto.dshmobile.local.chat

internal fun fullSharePersona(profile: PersonaProfile): PersonaProfile = profile.copy(
    id = PersonaProfile.DEFAULT_PERSONA_ID,
    name = profile.name.trim().take(80).ifBlank { "默认角色" },
    coreIdentity = profile.coreIdentity.trim().take(4_000),
    facts = profile.facts.filter { it.content.isNotBlank() }.map { it.copy(content = it.content.trim().take(4_000)) },
    portrait = profile.portrait.trim().take(4_000),
    lifeContext = profile.lifeContext.trim().take(4_000),
    attentionBiases = sharePersonaLines(profile.attentionBiases, 8, 240),
    attentionKeywords = sharePersonaLines(profile.attentionKeywords, 12, 120),
    perceptionBlindSpots = sharePersonaLines(profile.perceptionBlindSpots, 8, 240),
    quirks = sharePersonaLines(profile.quirks, 12, 240),
    limitations = sharePersonaLines(profile.limitations, 8, 240),
    coreValues = sharePersonaLines(profile.coreValues, 6, 240),
    coreTension = profile.coreTension.trim().take(2_000),
    stableTraits = sharePersonaLines(profile.stableTraits, 8, 240),
    mutableTraits = sharePersonaLines(profile.mutableTraits, 8, 240),
    initialUserImpression = profile.initialUserImpression.trim().take(2_000),
    voiceSamples = sharePersonaLines(profile.voiceSamples, 20, 240),
    worldSetting = profile.worldSetting.trim().take(4_000),
    franchise = profile.franchise.trim().take(120),
    timelinePosition = profile.timelinePosition.trim().take(2_000),
    knowledgeBoundary = sharePersonaLines(profile.knowledgeBoundary, 20, 240),
    loreEntries = shareLoreEntries(profile.loreEntries, 80),
    presetId = profile.presetId.trim().take(120),
    hardConstraints = sharePersonaLines(profile.hardConstraints, 20, 240),
    bannedPhrases = sharePersonaLines(profile.bannedPhrases, 30, 240),
    corrections = sharePersonaLines(profile.corrections, 20, 240),
    updatedAt = 0L,
)

internal fun compactSharePersona(profile: PersonaProfile): PersonaProfile = profile.copy(
    id = PersonaProfile.DEFAULT_PERSONA_ID,
    name = profile.name.trim().take(80).ifBlank { "默认角色" },
    coreIdentity = profile.coreIdentity.trim().take(420),
    facts = profile.facts.filter {
        it.category in listOf(
            CharacterFactCategories.PERSONALITY,
            CharacterFactCategories.VALUES_AND_TRADEOFFS,
            CharacterFactCategories.BIOGRAPHY,
            CharacterFactCategories.RELATIONSHIPS,
        )
    }.take(4).map { it.copy(content = it.content.take(300)) },
    portrait = profile.portrait.trim().take(320),
    lifeContext = profile.lifeContext.trim().take(220),
    attentionBiases = sharePersonaLines(profile.attentionBiases, 2, 80),
    attentionKeywords = sharePersonaLines(profile.attentionKeywords, 4, 60),
    perceptionBlindSpots = sharePersonaLines(profile.perceptionBlindSpots, 1, 80),
    quirks = sharePersonaLines(profile.quirks, 2, 80),
    limitations = sharePersonaLines(profile.limitations, 1, 80),
    coreValues = sharePersonaLines(profile.coreValues, 2, 80),
    coreTension = profile.coreTension.trim().take(160),
    stableTraits = sharePersonaLines(profile.stableTraits, 2, 80),
    mutableTraits = sharePersonaLines(profile.mutableTraits, 1, 80),
    initialUserImpression = profile.initialUserImpression.trim().take(120),
    voiceSamples = sharePersonaLines(profile.voiceSamples, 3, 100),
    worldSetting = profile.worldSetting.trim().take(180),
    franchise = profile.franchise.trim().take(60),
    timelinePosition = profile.timelinePosition.trim().take(100),
    knowledgeBoundary = sharePersonaLines(profile.knowledgeBoundary, 2, 80),
    loreEntries = emptyList(),
    presetId = profile.presetId.trim().take(80),
    hardConstraints = sharePersonaLines(profile.hardConstraints, 4, 80),
    bannedPhrases = sharePersonaLines(profile.bannedPhrases, 6, 40),
    corrections = emptyList(),
    updatedAt = 0L,
)

private fun shareLoreEntries(values: List<PersonaLoreEntry>, limit: Int): List<PersonaLoreEntry> =
    values.asSequence()
        .filter { it.content.isNotBlank() }
        .map { entry ->
            entry.copy(
                id = entry.id.trim().take(80),
                title = entry.title.trim().take(120),
                content = entry.content.trim().take(4_000),
                keywords = sharePersonaLines(entry.keywords, 16, 80),
                secondaryKeywords = sharePersonaLines(entry.secondaryKeywords, 16, 80),
                priority = entry.priority.coerceIn(0, 100),
                spoilerLevel = entry.spoilerLevel.coerceIn(0, 3),
            )
        }
        .take(limit)
        .toList()

private fun sharePersonaLines(values: List<String>, limit: Int, maxChars: Int): List<String> =
    values.asSequence()
        .map { it.trim().take(maxChars) }
        .filter(String::isNotBlank)
        .distinct()
        .take(limit)
        .toList()
