package com.labteto.dshmobile.local.chat

import android.content.Context
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun isMeaningfulGalleryPersona(persona: PersonaProfile): Boolean {
    val name = normalizePersonaText(persona.name)
    if (name.isBlank() || name in DEFAULT_PERSONA_NAMES) return false
    return persona.coreIdentity.isNotBlank() ||
        persona.facts.isNotEmpty() ||
        persona.portrait.isNotBlank() ||
        persona.lifeContext.isNotBlank() ||
        persona.attentionBiases.isNotEmpty() ||
        persona.attentionKeywords.isNotEmpty() ||
        persona.coreValues.isNotEmpty() ||
        persona.stableTraits.isNotEmpty() ||
        persona.worldSetting.isNotBlank() ||
        persona.franchise.isNotBlank() ||
        persona.timelinePosition.isNotBlank() ||
        persona.knowledgeBoundary.isNotEmpty() ||
        persona.loreEntries.isNotEmpty() ||
        persona.hardConstraints.isNotEmpty() ||
        persona.corrections.isNotEmpty()
}

/**
 * Compatibility matching is intentionally conservative. Once a session is bound to a gallery ID,
 * the stable ID wins. Name matching is only a first-save convenience and must not merge distinct
 * same-name world variants.
 */
internal fun samePersonaIdentity(left: PersonaProfile, right: PersonaProfile): Boolean {
    val leftName = normalizePersonaText(left.name)
    val rightName = normalizePersonaText(right.name)
    if (leftName.isBlank() || rightName.isBlank() || leftName != rightName) return false
    if (leftName in DEFAULT_PERSONA_NAMES || rightName in DEFAULT_PERSONA_NAMES) return false
    // V4 merges require an actual identity anchor; names and retired worldSetting text
    // are insufficient to merge distinct people or alternate-universe variants.
    if (left.coreIdentity.isBlank() || right.coreIdentity.isBlank()) return false
    return compatibleIdentityField(left.franchise, right.franchise) &&
        compatibleIdentityField(left.coreIdentity, right.coreIdentity)
}

private fun compatibleIdentityField(left: String, right: String): Boolean {
    val a = normalizePersonaText(left)
    val b = normalizePersonaText(right)
    if (a.isBlank() || b.isBlank()) return true
    return a == b || a.contains(b) || b.contains(a)
}

/** Preserve distinct facts; an existing user-authored fact always wins against automatic enrichment. */
internal fun mergeCharacterFacts(
    base: List<CharacterFact>,
    incoming: List<CharacterFact>,
): List<CharacterFact> {
    val byId = linkedMapOf<String, CharacterFact>()
    base.forEach { byId[it.id] = it }
    incoming.forEach { candidate ->
        val original = byId[candidate.id]
        if (original == null) byId[candidate.id] = candidate
        else if (original.provenance != CharacterFactProvenance.USER_CREATED &&
            candidate.provenance == CharacterFactProvenance.USER_CREATED) byId[candidate.id] = candidate
    }
    return byId.values.toList()
}

internal fun mergePersonaProfiles(base: PersonaProfile, incoming: PersonaProfile): PersonaProfile =
    base.copy(
        name = chooseDisplayName(base.name, incoming.name),
        coreIdentity = mergePersonaText(base.coreIdentity, incoming.coreIdentity, 4_000),
        facts = mergeCharacterFacts(base.facts, incoming.facts),
        portrait = mergePersonaText(base.portrait, incoming.portrait, 4_000),
        lifeContext = mergePersonaText(base.lifeContext, incoming.lifeContext, 4_000),
        attentionBiases = mergePersonaLines(base.attentionBiases, incoming.attentionBiases, 8),
        attentionKeywords = mergePersonaLines(base.attentionKeywords, incoming.attentionKeywords, 12),
        perceptionBlindSpots = mergePersonaLines(base.perceptionBlindSpots, incoming.perceptionBlindSpots, 8),
        quirks = mergePersonaLines(base.quirks, incoming.quirks, 12),
        limitations = mergePersonaLines(base.limitations, incoming.limitations, 8),
        coreValues = mergePersonaLines(base.coreValues, incoming.coreValues, 6),
        coreTension = mergePersonaText(base.coreTension, incoming.coreTension, 2_000),
        stableTraits = mergePersonaLines(base.stableTraits, incoming.stableTraits, 8),
        mutableTraits = mergePersonaLines(base.mutableTraits, incoming.mutableTraits, 8),
        initialUserImpression = mergePersonaText(base.initialUserImpression, incoming.initialUserImpression, 2_000),
        voiceSamples = mergePersonaLines(base.voiceSamples, incoming.voiceSamples, 20),
        worldSetting = mergePersonaText(base.worldSetting, incoming.worldSetting, 4_000),
        franchise = mergePersonaText(base.franchise, incoming.franchise, 120),
        timelinePosition = mergePersonaText(base.timelinePosition, incoming.timelinePosition, 2_000),
        knowledgeBoundary = mergePersonaLines(base.knowledgeBoundary, incoming.knowledgeBoundary, 20),
        loreEntries = mergeLoreEntries(base.loreEntries, incoming.loreEntries, 80),
        presetId = base.presetId.ifBlank { incoming.presetId }.take(120),
        behaviorTuning = mergeCharacterBehaviorTuning(base.behaviorTuning, incoming.behaviorTuning),
        hardConstraints = mergePersonaLines(base.hardConstraints, incoming.hardConstraints, 20),
        bannedPhrases = mergePersonaLines(base.bannedPhrases, incoming.bannedPhrases, 30),
        corrections = mergePersonaLines(base.corrections, incoming.corrections, 20),
        updatedAt = maxOf(base.updatedAt, incoming.updatedAt),
    )

internal fun applyPersonaSuggestions(
    profile: PersonaProfile,
    suggestions: List<PersonaAppendSuggestion>,
): PersonaProfile {
    var result = profile
    suggestions.forEach { suggestion ->
        val value = suggestion.value.trim()
        if (value.isBlank()) return@forEach
        result = when (suggestion.field) {
            "coreIdentity" -> result.copy(
                coreIdentity = mergePersonaText(result.coreIdentity, value, 4_000),
            )
            in CharacterFactCategories.canonical -> result.copy(facts = result.facts + CharacterFact(
                id = java.util.UUID.randomUUID().toString(),
                category = suggestion.field,
                content = value,
                provenance = CharacterFactProvenance.USER_CREATED,
            ))
            "portrait" -> result.copy(portrait = mergePersonaText(result.portrait, value, 4_000))
            "lifeContext" -> result.copy(lifeContext = mergePersonaText(result.lifeContext, value, 4_000))
            "attentionBiases" -> result.copy(attentionBiases = mergePersonaLines(result.attentionBiases, listOf(value), 8))
            "attentionKeywords" -> result.copy(attentionKeywords = mergePersonaLines(result.attentionKeywords, listOf(value), 12))
            "perceptionBlindSpots" -> result.copy(perceptionBlindSpots = mergePersonaLines(result.perceptionBlindSpots, listOf(value), 8))
            "quirks" -> result.copy(quirks = mergePersonaLines(result.quirks, listOf(value), 12))
            "limitations" -> result.copy(limitations = mergePersonaLines(result.limitations, listOf(value), 8))
            "coreValues" -> result.copy(coreValues = mergePersonaLines(result.coreValues, listOf(value), 6))
            "coreTension" -> result.copy(coreTension = mergePersonaText(result.coreTension, value, 2_000))
            "stableTraits" -> result.copy(stableTraits = mergePersonaLines(result.stableTraits, listOf(value), 8))
            "mutableTraits" -> result.copy(mutableTraits = mergePersonaLines(result.mutableTraits, listOf(value), 8))
            "initialUserImpression" -> result.copy(initialUserImpression = mergePersonaText(result.initialUserImpression, value, 2_000))
            "voiceSamples" -> result.copy(voiceSamples = mergePersonaLines(result.voiceSamples, listOf(value), 20))
            "worldSetting" -> result.copy(worldSetting = mergePersonaText(result.worldSetting, value, 4_000))
            "franchise" -> result.copy(franchise = mergePersonaText(result.franchise, value, 120))
            "timelinePosition" -> result.copy(timelinePosition = mergePersonaText(result.timelinePosition, value, 2_000))
            "knowledgeBoundary" -> result.copy(knowledgeBoundary = mergePersonaLines(result.knowledgeBoundary, listOf(value), 20))
            "hardConstraints" -> result.copy(hardConstraints = mergePersonaLines(result.hardConstraints, listOf(value), 20))
            "bannedPhrases" -> result.copy(bannedPhrases = mergePersonaLines(result.bannedPhrases, listOf(value), 30))
            "corrections" -> result.copy(corrections = mergePersonaLines(result.corrections, listOf(value), 20))
            else -> result
        }
    }
    return result
}

internal fun mergeChatState(base: ChatCharacterState, incoming: ChatCharacterState): ChatCharacterState {
    val incomingIsNewer = incoming.updatedAt >= base.updatedAt
    val newer = if (incomingIsNewer) incoming else base
    val older = if (incomingIsNewer) base else incoming
    return newer.copy(evolution = mergeCharacterEvolution(older.evolution, newer.evolution),
        unresolvedThreads = mergePersonaLines(older.unresolvedThreads, newer.unresolvedThreads, 8),
        dynamics = newer.dynamics.copy(
            facts = mergeEvidence(older.dynamics.facts, newer.dynamics.facts, 24),
            hypotheses = mergeEvidence(older.dynamics.hypotheses, newer.dynamics.hypotheses, 16),
            unknowns = mergePersonaLines(older.dynamics.unknowns, newer.dynamics.unknowns, 12),
            sharedMoments = mergePersonaLines(older.dynamics.sharedMoments, newer.dynamics.sharedMoments, 30),
            sharedObjects = mergePersonaLines(older.dynamics.sharedObjects, newer.dynamics.sharedObjects, 30),
        ),
    ).canonicalizeLegacyCharacterState().withoutLegacyConversationContext()
}

private fun mergeGalleryChatContext(
    base: ChatContextState,
    incoming: ChatContextState,
): ChatContextState {
    val left = base.normalized()
    val right = incoming.normalized()
    if (!left.hasUsefulFacts()) return right
    if (!right.hasUsefulFacts()) return left
    return right.copy(
        // A story with no stage metadata cannot erase a known plot path when
        // archives merge; the gallery save path already handles explicit stage resets.
        storyStage = right.storyStage.ifBlank { left.storyStage },
        unlockedStoryStages = if (right.storyStage.isNotBlank()) {
            right.unlockedStoryStages
        } else left.unlockedStoryStages,
        scene = right.scene.copy(
            sceneTime = right.scene.sceneTime.ifBlank { left.scene.sceneTime },
            location = right.scene.location.ifBlank { left.scene.location },
        ),
        continuity = right.continuity.copy(
            recentEvents = mergePersonaLines(left.continuity.recentEvents, right.continuity.recentEvents, 5),
            decisions = mergePersonaLines(left.continuity.decisions, right.continuity.decisions, 4),
            unfinished = mergePersonaLines(left.continuity.unfinished, right.continuity.unfinished, 4),
        ),
        pendingTurns = emptyList(),
        pendingThroughSequence = -1L,
        pendingArchiveReady = false,
    ).normalized()
}

internal fun mergeGalleryStories(
    base: PersonaGalleryStory,
    incoming: PersonaGalleryStory,
): PersonaGalleryStory {
    val excluded = mergePersonaLines(base.excludedMessageKeys, incoming.excludedMessageKeys, MAX_GALLERY_EXCLUDED_MESSAGE_KEYS)
    return base.copy(
        title = incoming.title.ifBlank { base.title },
        notes = mergePersonaText(base.notes, incoming.notes, 4_000),
        history = mergeHistory(base.history, incoming.history)
            .filterNot { galleryMessageArchiveKey(it) in excluded },
        chatState = mergeChatState(base.chatState, incoming.chatState),
        chatContext = mergeGalleryChatContext(base.chatContext, incoming.chatContext),
        sourceSessionIds = mergePersonaLines(base.sourceSessionIds, incoming.sourceSessionIds, 40),
        excludedMessageKeys = excluded,
        updatedAt = maxOf(base.updatedAt, incoming.updatedAt),
    )
}

internal fun mergeGalleryStoryLists(
    base: List<PersonaGalleryStory>,
    incoming: List<PersonaGalleryStory>,
): List<PersonaGalleryStory> {
    val result = base.toMutableList()
    incoming.forEach { candidate ->
        val matchIndex = result.indexOfFirst { existing ->
            val sameId = candidate.id.isNotBlank() && existing.id == candidate.id
            val sharedSession = existing.sourceSessionIds.any { it in candidate.sourceSessionIds }
            val existingKeys = existing.history.mapTo(linkedSetOf(), ::galleryMessageArchiveKey)
            val candidateKeys = candidate.history.mapTo(linkedSetOf(), ::galleryMessageArchiveKey)
            val sameArchive = existingKeys.isNotEmpty() && existingKeys == candidateKeys
            sameId || sharedSession || sameArchive
        }
        if (matchIndex >= 0) {
            result[matchIndex] = mergeGalleryStories(result[matchIndex], candidate)
        } else {
            result += candidate.copy(
                id = candidate.id.takeUnless { id -> result.any { it.id == id } }
                    ?: "story-${UUID.randomUUID()}",
            )
        }
    }
    return result.sortedByDescending(PersonaGalleryStory::updatedAt)
}

internal fun galleryMessageArchiveKey(message: LocalHarnessMessage): String =
    message.id.takeIf(String::isNotBlank)
        ?: "${message.role}|${message.createdAt}|${normalizePersonaText(message.content)}"

internal fun removeArchivedGalleryMessage(
    story: PersonaGalleryStory,
    messageKey: String,
): PersonaGalleryStory? {
    val remaining = story.history.filterNot { galleryMessageArchiveKey(it) == messageKey }
    if (remaining.size == story.history.size) return null
    return story.copy(
        history = remaining,
        excludedMessageKeys = mergePersonaLines(story.excludedMessageKeys, listOf(messageKey), MAX_GALLERY_EXCLUDED_MESSAGE_KEYS),
    )
}

internal fun galleryEntryHasUnsavedChanges(
    entry: PersonaGalleryEntry,
    storyId: String?,
    persona: PersonaProfile,
    history: List<LocalHarnessMessage>,
    chatState: ChatCharacterState,
): Boolean {
    if (personaContentSignature(entry.persona) != personaContentSignature(persona)) return true
    val relevant = history.filter { it.role == "user" || it.role == "assistant" }
    if (storyId == null) return relevant.isNotEmpty()
    val story = entry.stories.firstOrNull { it.id == storyId } ?: return relevant.isNotEmpty()
    val archived = story.history.mapTo(hashSetOf(), ::galleryMessageArchiveKey)
    if (relevant.any { galleryMessageArchiveKey(it) !in archived && galleryMessageArchiveKey(it) !in story.excludedMessageKeys }) {
        return true
    }
    return chatState.updatedAt > story.chatState.updatedAt
}

private fun personaContentSignature(persona: PersonaProfile): String = listOf(
    persona.name,
    persona.coreIdentity,
    persona.facts.joinToString("\u0000") { fact ->
        listOf(
            fact.id,
            fact.category,
            fact.content,
            fact.relatedFactIds.joinToString("|"),
            fact.perspective,
            fact.temporalScope,
            fact.provenance.name,
            fact.sourceReference,
        ).joinToString("~")
    },
    persona.portrait,
    persona.lifeContext,
    persona.attentionBiases.joinToString("\u0000"),
    persona.attentionKeywords.joinToString("\u0000"),
    persona.perceptionBlindSpots.joinToString("\u0000"),
    persona.quirks.joinToString("\u0000"),
    persona.limitations.joinToString("\u0000"),
    persona.coreValues.joinToString("\u0000"),
    persona.coreTension,
    persona.stableTraits.joinToString("\u0000"),
    persona.mutableTraits.joinToString("\u0000"),
    persona.initialUserImpression,
    persona.voiceSamples.joinToString("\u0000"),
    persona.worldSetting,
    persona.franchise,
    persona.timelinePosition,
    persona.knowledgeBoundary.joinToString("\u0000"),
    persona.loreEntries.joinToString("\u0000") { entry ->
        listOf(
            entry.id, entry.title, entry.content,
            entry.keywords.joinToString("|"), entry.secondaryKeywords.joinToString("|"),
            entry.priority.toString(), entry.alwaysOn.toString(), entry.spoilerLevel.toString(),
        ).joinToString("~")
    },
    persona.presetId + "|" + persona.behaviorTuning.signature(),
    persona.hardConstraints.joinToString("\u0000"),
    persona.bannedPhrases.joinToString("\u0000"),
    persona.corrections.joinToString("\u0000"),
).joinToString("\u0001") { normalizePersonaText(it) }

internal fun defaultStoryTitle(history: List<LocalHarnessMessage>): String =
    history.firstOrNull { it.role == "user" && it.content.isNotBlank() }
        ?.content
        ?.lineSequence()
        ?.firstOrNull()
        ?.trim()
        ?.take(28)
        .orEmpty()

private fun mergeEvidence(
    base: List<RelationshipEvidence>,
    incoming: List<RelationshipEvidence>,
    limit: Int,
): List<RelationshipEvidence> {
    val result = mutableListOf<RelationshipEvidence>()
    val index = linkedMapOf<String, Int>()
    (base + incoming).forEach { item ->
        val key = normalizePersonaText(item.text)
        if (key.isBlank()) return@forEach
        val previousIndex = index[key]
        if (previousIndex == null) {
            index[key] = result.size
            result += item
        } else if (item.confidence > result[previousIndex].confidence) {
            result[previousIndex] = item
        }
    }
    return result.takeLast(limit)
}

internal fun mergeHistory(
    base: List<LocalHarnessMessage>,
    incoming: List<LocalHarnessMessage>,
): List<LocalHarnessMessage> {
    val seenIds = linkedSetOf<String>()
    val seenContent = linkedSetOf<String>()
    return (base + incoming)
        .asSequence()
        .filter { it.role == "user" || it.role == "assistant" }
        .filter { message ->
            val idUnique = message.id.isBlank() || seenIds.add(message.id)
            val contentKey = "${message.role}|${message.createdAt}|${normalizePersonaText(message.content)}"
            idUnique && seenContent.add(contentKey)
        }
        .sortedBy(LocalHarnessMessage::createdAt)
        .toList()
}

private fun chooseDisplayName(base: String, incoming: String): String {
    val current = base.trim()
    val fresh = incoming.trim()
    if (current.isBlank()) return fresh
    if (fresh.isBlank()) return current
    if (normalizePersonaText(current) in DEFAULT_PERSONA_NAMES &&
        normalizePersonaText(fresh) !in DEFAULT_PERSONA_NAMES
    ) return fresh
    return if (fresh.length > current.length && normalizePersonaText(fresh).contains(normalizePersonaText(current))) {
        fresh
    } else current
}

private fun mergePersonaText(base: String, incoming: String, limit: Int): String {
    val current = base.trim()
    val fresh = incoming.trim()
    if (current.isBlank()) return fresh.take(limit)
    if (fresh.isBlank()) return current.take(limit)

    val currentKey = normalizePersonaText(current)
    val freshKey = normalizePersonaText(fresh)
    if (currentKey == freshKey || currentKey.contains(freshKey)) return current.take(limit)
    if (freshKey.contains(currentKey)) return fresh.take(limit)

    val existingClauses = splitPersonaClauses(current)
    val known = existingClauses.mapTo(linkedSetOf(), ::normalizePersonaText)
    val additions = splitPersonaClauses(fresh).filter { known.add(normalizePersonaText(it)) }
    if (additions.isEmpty()) return current.take(limit)
    return (existingClauses + additions).joinToString("；").take(limit)
}

private fun mergeLoreEntries(
    base: List<PersonaLoreEntry>,
    incoming: List<PersonaLoreEntry>,
    limit: Int,
): List<PersonaLoreEntry> {
    val merged = linkedMapOf<String, PersonaLoreEntry>()
    (base + incoming).forEach { entry ->
        val key = normalizePersonaText(entry.id.ifBlank { entry.title.ifBlank { entry.content.take(80) } })
        if (key.isBlank() || entry.content.isBlank()) return@forEach
        val existing = merged[key]
        merged[key] = if (existing == null) {
            entry
        } else {
            existing.copy(
                title = mergePersonaText(existing.title, entry.title, 120),
                content = mergePersonaText(existing.content, entry.content, 4_000),
                keywords = mergePersonaLines(existing.keywords, entry.keywords, 16),
                secondaryKeywords = mergePersonaLines(existing.secondaryKeywords, entry.secondaryKeywords, 16),
                priority = maxOf(existing.priority, entry.priority).coerceIn(0, 100),
                alwaysOn = existing.alwaysOn || entry.alwaysOn,
                spoilerLevel = maxOf(existing.spoilerLevel, entry.spoilerLevel).coerceIn(0, 3),
            )
        }
    }
    return merged.values.toList().takeLast(limit)
}

internal fun mergePersonaLines(base: List<String>, incoming: List<String>, limit: Int): List<String> {
    val seen = linkedSetOf<String>()
    return (base + incoming)
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filter { seen.add(normalizePersonaText(it)) }
        .map { it.take(240) }
        .toList()
        .takeLast(limit)
}

private fun splitPersonaClauses(text: String): List<String> =
    text.split(Regex("""[；;。\n]+"""))
        .map(String::trim)
        .filter(String::isNotBlank)

private fun normalizePersonaText(text: String): String =
    text.lowercase()
        .replace("的", "")
        .replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】—_-]+"""), "")

internal const val MAX_PERSONA_IMPORT_CHARS = 64_000
internal const val MAX_GALLERY_EXCLUDED_MESSAGE_KEYS = 10_000

private val DEFAULT_PERSONA_NAMES = setOf(
    normalizePersonaText("默认角色"),
    normalizePersonaText("default"),
    normalizePersonaText("default角色"),
)

