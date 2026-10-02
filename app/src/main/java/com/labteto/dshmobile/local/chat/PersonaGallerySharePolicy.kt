package com.labteto.dshmobile.local.chat

internal fun fullSharePersona(profile: PersonaProfile): PersonaProfile = profile.copy(
    id = PersonaProfile.DEFAULT_PERSONA_ID,
    name = profile.name.trim().take(80).ifBlank { "默认角色" },
    identity = profile.identity.trim().take(2_000),
    background = profile.background.trim().take(4_000),
    personality = profile.personality.trim().take(2_000),
    speechStyle = profile.speechStyle.trim().take(2_000),
    relationship = profile.relationship.trim().take(2_000),
    worldSetting = profile.worldSetting.trim().take(4_000),
    franchise = profile.franchise.trim().take(120),
    timelinePosition = profile.timelinePosition.trim().take(2_000),
    coreMotivations = sharePersonaLines(profile.coreMotivations, 12, 240),
    valuePriorities = sharePersonaLines(profile.valuePriorities, 12, 240),
    behaviorPatterns = sharePersonaLines(profile.behaviorPatterns, 20, 240),
    internalContradictions = sharePersonaLines(profile.internalContradictions, 12, 240),
    knowledgeBoundary = sharePersonaLines(profile.knowledgeBoundary, 20, 240),
    loreEntries = shareLoreEntries(profile.loreEntries, 80),
    presetId = profile.presetId.trim().take(120),
    hardConstraints = sharePersonaLines(profile.hardConstraints, 20, 240),
    exampleDialogues = sharePersonaLines(profile.exampleDialogues, 12, 240),
    bannedPhrases = sharePersonaLines(profile.bannedPhrases, 30, 240),
    signaturePhrases = sharePersonaLines(profile.signaturePhrases, 20, 240),
    corrections = sharePersonaLines(profile.corrections, 20, 240),
    updatedAt = 0L,
)

internal fun compactSharePersona(profile: PersonaProfile): PersonaProfile = profile.copy(
    id = PersonaProfile.DEFAULT_PERSONA_ID,
    name = profile.name.trim().take(80).ifBlank { "默认角色" },
    identity = profile.identity.trim().take(160),
    background = profile.background.trim().take(180),
    personality = profile.personality.trim().take(160),
    speechStyle = profile.speechStyle.trim().take(160),
    relationship = profile.relationship.trim().take(120),
    worldSetting = profile.worldSetting.trim().take(180),
    franchise = profile.franchise.trim().take(60),
    timelinePosition = profile.timelinePosition.trim().take(100),
    coreMotivations = sharePersonaLines(profile.coreMotivations, 2, 60),
    valuePriorities = sharePersonaLines(profile.valuePriorities, 2, 60),
    behaviorPatterns = sharePersonaLines(profile.behaviorPatterns, 2, 60),
    internalContradictions = sharePersonaLines(profile.internalContradictions, 1, 60),
    knowledgeBoundary = sharePersonaLines(profile.knowledgeBoundary, 2, 60),
    loreEntries = emptyList(),
    presetId = profile.presetId.trim().take(80),
    hardConstraints = sharePersonaLines(profile.hardConstraints, 4, 60),
    exampleDialogues = sharePersonaLines(profile.exampleDialogues, 2, 80),
    bannedPhrases = sharePersonaLines(profile.bannedPhrases, 6, 30),
    signaturePhrases = sharePersonaLines(profile.signaturePhrases, 4, 40),
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
