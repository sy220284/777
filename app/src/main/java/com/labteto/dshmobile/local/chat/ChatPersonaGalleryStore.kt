package com.labteto.dshmobile.local.chat

import android.content.Context
import com.labteto.dshmobile.local.LocalHarnessMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PersonaGalleryStory(
    val id: String,
    val title: String = "",
    val notes: String = "",
    val history: List<LocalHarnessMessage> = emptyList(),
    val chatState: ChatCharacterState = ChatCharacterState(),
    val sourceSessionIds: List<String> = emptyList(),
    val excludedMessageKeys: List<String> = emptyList(),
    val updatedAt: Long = 0L,
) {
    fun context(personaName: String): String = buildString {
        if (notes.isNotBlank()) appendLine("剧情提要：${notes.trim().take(2_500)}")
        if (chatState.updatedAt > 0L) {
            appendLine("保存时的关系：${chatState.relationshipState.take(80)}")
            chatState.dynamics.sharedMoments.takeLast(6).takeIf { it.isNotEmpty() }?.let {
                appendLine("已发生的共同经历：${it.joinToString("；").take(800)}")
            }
            chatState.unresolvedThreads.takeLast(3).takeIf { it.isNotEmpty() }?.let {
                appendLine("仍待推进的线索：${it.joinToString("；").take(400)}")
            }
        }
        val excerpt = history.asReversed().asSequence()
            .filter { it.role == "user" || it.role == "assistant" }
            .map { "${if (it.role == "user") "用户" else personaName}：${it.content.trim().take(600)}" }
            .take(16).toList().asReversed().joinToString("\n").takeLast(3_000)
        if (excerpt.isNotBlank()) appendLine("已保存故事的最近对话摘录：\n$excerpt")
    }.trim()
}

/** One durable character master profile. Story timelines are isolated under [stories]. */
@Serializable
data class PersonaGalleryEntry(
    val id: String,
    val persona: PersonaProfile,
    val stories: List<PersonaGalleryStory> = emptyList(),
    // V3 compatibility fields. They are migrated into stories and cleared on the first V4 read.
    val storyNotes: String = "",
    val history: List<LocalHarnessMessage> = emptyList(),
    val chatState: ChatCharacterState = ChatCharacterState(),
    val sourceSessionId: String = "",
    val updatedAt: Long = 0L,
) {
    fun story(storyId: String?): PersonaGalleryStory? =
        storyId?.let { id -> stories.firstOrNull { it.id == id } }
            ?: stories.maxByOrNull(PersonaGalleryStory::updatedAt)

    fun storyContext(storyId: String? = null): String =
        story(storyId)?.context(persona.name).orEmpty()

    fun totalDialogueCount(): Int = stories.sumOf { story ->
        story.history.count { it.role == "user" || it.role == "assistant" }
    }
}

data class PersonaGallerySaveOutcome(
    val entry: PersonaGalleryEntry,
    val storyId: String?,
)

@Serializable
internal data class PersonaShareEnvelope(
    val schema: Int = 1,
    val source: String = "神言神语",
    val persona: PersonaProfile,
)

@Serializable
private data class GalleryDocument(
    val version: Int = 4,
    val entries: List<PersonaGalleryEntry> = emptyList(),
)

internal fun isMeaningfulGalleryPersona(persona: PersonaProfile): Boolean {
    val name = normalizePersonaText(persona.name)
    if (name.isBlank() || name in DEFAULT_PERSONA_NAMES) return false
    return persona.identity.isNotBlank() ||
        persona.background.isNotBlank() ||
        persona.personality.isNotBlank() ||
        persona.speechStyle.isNotBlank() ||
        persona.relationship.isNotBlank() ||
        persona.worldSetting.isNotBlank() ||
        persona.coreMotivations.isNotEmpty() ||
        persona.behaviorPatterns.isNotEmpty() ||
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
    return compatibleIdentityField(left.worldSetting, right.worldSetting)
}

private fun compatibleIdentityField(left: String, right: String): Boolean {
    val a = normalizePersonaText(left)
    val b = normalizePersonaText(right)
    if (a.isBlank() || b.isBlank()) return true
    return a == b || a.contains(b) || b.contains(a)
}

internal fun mergePersonaProfiles(base: PersonaProfile, incoming: PersonaProfile): PersonaProfile =
    base.copy(
        name = chooseDisplayName(base.name, incoming.name),
        identity = mergePersonaText(base.identity, incoming.identity, 2_000),
        background = mergePersonaText(base.background, incoming.background, 4_000),
        personality = mergePersonaText(base.personality, incoming.personality, 2_000),
        speechStyle = mergePersonaText(base.speechStyle, incoming.speechStyle, 2_000),
        relationship = mergePersonaText(base.relationship, incoming.relationship, 2_000),
        worldSetting = mergePersonaText(base.worldSetting, incoming.worldSetting, 4_000),
        franchise = mergePersonaText(base.franchise, incoming.franchise, 120),
        timelinePosition = mergePersonaText(base.timelinePosition, incoming.timelinePosition, 2_000),
        coreMotivations = mergePersonaLines(base.coreMotivations, incoming.coreMotivations, 12),
        valuePriorities = mergePersonaLines(base.valuePriorities, incoming.valuePriorities, 12),
        behaviorPatterns = mergePersonaLines(base.behaviorPatterns, incoming.behaviorPatterns, 20),
        internalContradictions = mergePersonaLines(base.internalContradictions, incoming.internalContradictions, 12),
        knowledgeBoundary = mergePersonaLines(base.knowledgeBoundary, incoming.knowledgeBoundary, 20),
        loreEntries = mergeLoreEntries(base.loreEntries, incoming.loreEntries, 80),
        presetId = base.presetId.ifBlank { incoming.presetId }.take(120),
        hardConstraints = mergePersonaLines(base.hardConstraints, incoming.hardConstraints, 20),
        exampleDialogues = mergePersonaLines(base.exampleDialogues, incoming.exampleDialogues, 12),
        bannedPhrases = mergePersonaLines(base.bannedPhrases, incoming.bannedPhrases, 30),
        signaturePhrases = mergePersonaLines(base.signaturePhrases, incoming.signaturePhrases, 20),
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
            "identity" -> result.copy(identity = mergePersonaText(result.identity, value, 2_000))
            "background" -> result.copy(background = mergePersonaText(result.background, value, 4_000))
            "personality" -> result.copy(personality = mergePersonaText(result.personality, value, 2_000))
            "speechStyle" -> result.copy(speechStyle = mergePersonaText(result.speechStyle, value, 2_000))
            "relationship" -> result.copy(relationship = mergePersonaText(result.relationship, value, 2_000))
            "worldSetting" -> result.copy(worldSetting = mergePersonaText(result.worldSetting, value, 4_000))
            "franchise" -> result.copy(franchise = mergePersonaText(result.franchise, value, 120))
            "timelinePosition" -> result.copy(timelinePosition = mergePersonaText(result.timelinePosition, value, 2_000))
            "coreMotivations" -> result.copy(coreMotivations = mergePersonaLines(result.coreMotivations, listOf(value), 12))
            "valuePriorities" -> result.copy(valuePriorities = mergePersonaLines(result.valuePriorities, listOf(value), 12))
            "behaviorPatterns" -> result.copy(behaviorPatterns = mergePersonaLines(result.behaviorPatterns, listOf(value), 20))
            "internalContradictions" -> result.copy(internalContradictions = mergePersonaLines(result.internalContradictions, listOf(value), 12))
            "knowledgeBoundary" -> result.copy(knowledgeBoundary = mergePersonaLines(result.knowledgeBoundary, listOf(value), 20))
            "hardConstraints" -> result.copy(hardConstraints = mergePersonaLines(result.hardConstraints, listOf(value), 20))
            "exampleDialogues" -> result.copy(exampleDialogues = mergePersonaLines(result.exampleDialogues, listOf(value), 12))
            "bannedPhrases" -> result.copy(bannedPhrases = mergePersonaLines(result.bannedPhrases, listOf(value), 30))
            "signaturePhrases" -> result.copy(signaturePhrases = mergePersonaLines(result.signaturePhrases, listOf(value), 20))
            "corrections" -> result.copy(corrections = mergePersonaLines(result.corrections, listOf(value), 20))
            else -> result
        }
    }
    return result
}

private fun mergeChatState(base: ChatCharacterState, incoming: ChatCharacterState): ChatCharacterState {
    val incomingIsNewer = incoming.updatedAt >= base.updatedAt
    val newer = if (incomingIsNewer) incoming else base
    val older = if (incomingIsNewer) base else incoming
    return newer.copy(
        unresolvedThreads = mergePersonaLines(older.unresolvedThreads, newer.unresolvedThreads, 8),
        dynamics = newer.dynamics.copy(
            facts = mergeEvidence(older.dynamics.facts, newer.dynamics.facts, 24),
            hypotheses = mergeEvidence(older.dynamics.hypotheses, newer.dynamics.hypotheses, 16),
            unknowns = mergePersonaLines(older.dynamics.unknowns, newer.dynamics.unknowns, 12),
            sharedMoments = mergePersonaLines(older.dynamics.sharedMoments, newer.dynamics.sharedMoments, 30),
        ),
    )
}

internal fun mergeGalleryStories(
    base: PersonaGalleryStory,
    incoming: PersonaGalleryStory,
): PersonaGalleryStory {
    val excluded = mergePersonaLines(base.excludedMessageKeys, incoming.excludedMessageKeys, 200)
    return base.copy(
        title = incoming.title.ifBlank { base.title },
        notes = mergePersonaText(base.notes, incoming.notes, 4_000),
        history = mergeHistory(base.history, incoming.history)
            .filterNot { galleryMessageArchiveKey(it) in excluded },
        chatState = mergeChatState(base.chatState, incoming.chatState),
        sourceSessionIds = mergePersonaLines(base.sourceSessionIds, incoming.sourceSessionIds, 40),
        excludedMessageKeys = excluded,
        updatedAt = maxOf(base.updatedAt, incoming.updatedAt),
    )
}

private fun migrateLegacyEntry(entry: PersonaGalleryEntry): PersonaGalleryEntry {
    if (entry.stories.isNotEmpty()) {
        return entry.copy(
            storyNotes = "",
            history = emptyList(),
            chatState = ChatCharacterState(),
            sourceSessionId = "",
        )
    }
    val hasLegacyStory = entry.storyNotes.isNotBlank() ||
        entry.history.isNotEmpty() ||
        entry.chatState.updatedAt > 0L ||
        entry.sourceSessionId.isNotBlank()
    val migratedStory = if (hasLegacyStory) {
        PersonaGalleryStory(
            id = "story-${UUID.randomUUID()}",
            title = defaultStoryTitle(entry.history),
            notes = entry.storyNotes,
            history = entry.history.filter { it.role == "user" || it.role == "assistant" },
            chatState = entry.chatState,
            sourceSessionIds = listOf(entry.sourceSessionId).filter(String::isNotBlank),
            updatedAt = entry.updatedAt,
        )
    } else {
        null
    }
    return entry.copy(
        stories = listOfNotNull(migratedStory),
        storyNotes = "",
        history = emptyList(),
        chatState = ChatCharacterState(),
        sourceSessionId = "",
    )
}

/** V3-only migration: collapse the duplicate same-name cards created by the old snapshot model. */
internal fun compactLegacyDuplicateGalleryEntries(
    entries: List<PersonaGalleryEntry>,
): List<PersonaGalleryEntry> {
    if (entries.size < 2) return entries.map(::migrateLegacyEntry)
    val compacted = mutableListOf<PersonaGalleryEntry>()
    entries.sortedByDescending { it.updatedAt }.forEach { raw ->
        val entry = migrateLegacyEntry(raw)
        val duplicateIndex = compacted.indexOfFirst { existing ->
            samePersonaIdentity(existing.persona, entry.persona)
        }
        if (duplicateIndex < 0) {
            compacted += entry
        } else {
            val canonical = compacted[duplicateIndex]
            compacted[duplicateIndex] = canonical.copy(
                persona = mergePersonaProfiles(canonical.persona, entry.persona).copy(id = canonical.id),
                stories = mergeLegacyStoryLists(canonical.stories, entry.stories),
                updatedAt = maxOf(canonical.updatedAt, entry.updatedAt),
            )
        }
    }
    return compacted.sortedByDescending { it.updatedAt }
}

private fun mergeLegacyStoryLists(
    base: List<PersonaGalleryStory>,
    incoming: List<PersonaGalleryStory>,
): List<PersonaGalleryStory> {
    val result = base.toMutableList()
    incoming.forEach { candidate ->
        val matchIndex = result.indexOfFirst { existing ->
            val sharedSession = existing.sourceSessionIds.any { it in candidate.sourceSessionIds }
            val existingKeys = existing.history.mapTo(linkedSetOf(), ::galleryMessageArchiveKey)
            val candidateKeys = candidate.history.mapTo(linkedSetOf(), ::galleryMessageArchiveKey)
            val sameArchive = existingKeys.isNotEmpty() && existingKeys == candidateKeys
            sharedSession || sameArchive
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
        excludedMessageKeys = mergePersonaLines(story.excludedMessageKeys, listOf(messageKey), 200),
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
    persona.identity,
    persona.background,
    persona.personality,
    persona.speechStyle,
    persona.relationship,
    persona.worldSetting,
    persona.franchise,
    persona.timelinePosition,
    persona.coreMotivations.joinToString("\u0000"),
    persona.valuePriorities.joinToString("\u0000"),
    persona.behaviorPatterns.joinToString("\u0000"),
    persona.internalContradictions.joinToString("\u0000"),
    persona.knowledgeBoundary.joinToString("\u0000"),
    persona.loreEntries.joinToString("\u0000") { entry ->
        listOf(
            entry.id, entry.title, entry.content,
            entry.keywords.joinToString("|"), entry.secondaryKeywords.joinToString("|"),
            entry.priority.toString(), entry.alwaysOn.toString(), entry.spoilerLevel.toString(),
        ).joinToString("~")
    },
    persona.presetId,
    persona.hardConstraints.joinToString("\u0000"),
    persona.exampleDialogues.joinToString("\u0000"),
    persona.bannedPhrases.joinToString("\u0000"),
    persona.signaturePhrases.joinToString("\u0000"),
    persona.corrections.joinToString("\u0000"),
).joinToString("\u0001") { normalizePersonaText(it) }

private fun defaultStoryTitle(history: List<LocalHarnessMessage>): String =
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

private fun mergeHistory(
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
                spoilerLevel = minOf(existing.spoilerLevel, entry.spoilerLevel).coerceIn(0, 3),
            )
        }
    }
    return merged.values.takeLast(limit)
}

private fun mergePersonaLines(base: List<String>, incoming: List<String>, limit: Int): List<String> {
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

private const val MAX_PERSONA_IMPORT_CHARS = 64_000

private val DEFAULT_PERSONA_NAMES = setOf(
    normalizePersonaText("默认角色"),
    normalizePersonaText("default"),
    normalizePersonaText("default角色"),
)

@Singleton
class ChatPersonaGalleryStore internal constructor(
    private val file: File,
    private val json: Json,
) {
    @Inject constructor(@ApplicationContext context: Context, json: Json) :
        this(File(context.filesDir, "local-harness/chat/persona-gallery.json"), json)

    private val durableFile = RecoveringChatDocumentFile(file)

    @Synchronized
    fun list(): List<PersonaGalleryEntry> = readNormalized().entries.sortedByDescending { it.updatedAt }

    @Synchronized
    fun exportPersona(id: String, compact: Boolean = false): String {
        val entry = readNormalized().entries.firstOrNull { it.id == id }
            ?: error("人物档案不存在")
        val profile = if (compact) compactSharePersona(entry.persona) else fullSharePersona(entry.persona)
        return json.encodeToString(
            PersonaShareEnvelope.serializer(),
            PersonaShareEnvelope(persona = profile.copy(id = PersonaProfile.DEFAULT_PERSONA_ID)),
        )
    }

    @Synchronized
    fun importPersona(payload: String): PersonaGalleryEntry {
        val cleanPayload = payload.trim()
        require(cleanPayload.isNotEmpty() && cleanPayload.length <= MAX_PERSONA_IMPORT_CHARS) {
            "人物分享数据为空或过大"
        }
        val envelope = runCatching {
            json.decodeFromString(PersonaShareEnvelope.serializer(), cleanPayload)
        }.getOrElse { error ->
            throw IllegalArgumentException("人物分享数据格式不正确", error)
        }
        require(envelope.schema == 1) { "暂不支持这个人物分享版本" }

        val now = System.currentTimeMillis()
        val imported = fullSharePersona(envelope.persona).copy(updatedAt = now)
        require(isMeaningfulGalleryPersona(imported)) { "人物设定内容不足，无法导入" }

        val doc = readNormalized()
        val matched = doc.entries.filter { samePersonaIdentity(it.persona, imported) }.singleOrNull()
        val entryId = matched?.id ?: "gallery-${UUID.randomUUID()}"
        val entry = if (matched != null) {
            matched.copy(
                persona = mergePersonaProfiles(matched.persona, imported)
                    .copy(id = entryId, updatedAt = now),
                updatedAt = now,
            )
        } else {
            PersonaGalleryEntry(
                id = entryId,
                persona = imported.copy(id = entryId),
                updatedAt = now,
            )
        }
        write(doc.copy(version = 4, entries = doc.entries.filterNot { it.id == entryId } + entry))
        return entry
    }

    @Synchronized
    fun save(
        persona: PersonaProfile,
        sourceSessionId: String,
        history: List<LocalHarnessMessage>,
        chatState: ChatCharacterState,
        notes: String,
        existingId: String? = null,
        existingStoryId: String? = null,
        forceNewStory: Boolean = false,
    ): PersonaGallerySaveOutcome {
        val doc = readNormalized()
        val explicit = existingId?.let { id -> doc.entries.firstOrNull { it.id == id } }
        val compatible = if (explicit == null) {
            doc.entries.filter { samePersonaIdentity(it.persona, persona) }.singleOrNull()
        } else null
        val matched = explicit ?: compatible
        val entryId = matched?.id ?: "gallery-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val baseEntry = matched ?: PersonaGalleryEntry(
            id = entryId,
            persona = persona.copy(id = entryId),
            updatedAt = now,
        )

        val explicitStory = existingStoryId
            ?.takeUnless { forceNewStory }
            ?.let { id -> baseEntry.stories.firstOrNull { it.id == id } }
        val sessionStory = if (explicitStory == null && !forceNewStory) {
            baseEntry.stories.firstOrNull { sourceSessionId in it.sourceSessionIds }
        } else null
        val baseStory = explicitStory ?: sessionStory
        val excluded = baseStory?.excludedMessageKeys.orEmpty()
        val incomingHistory = history
            .filter { it.role == "user" || it.role == "assistant" }
            .filterNot { galleryMessageArchiveKey(it) in excluded }
        val shouldSaveStory = forceNewStory ||
            baseStory != null ||
            incomingHistory.isNotEmpty() ||
            notes.isNotBlank() ||
            chatState.updatedAt > 0L
        if (!shouldSaveStory) {
            val entry = baseEntry.copy(
                persona = mergePersonaProfiles(baseEntry.persona, persona)
                    .copy(id = entryId, updatedAt = now),
                updatedAt = now,
            )
            write(doc.copy(version = 4, entries = doc.entries.filterNot { it.id == entryId } + entry))
            return PersonaGallerySaveOutcome(entry = entry, storyId = null)
        }

        val storyId = baseStory?.id ?: "story-${UUID.randomUUID()}"
        val incomingStory = PersonaGalleryStory(
            id = storyId,
            title = baseStory?.title?.takeIf(String::isNotBlank)
                ?: defaultStoryTitle(incomingHistory),
            notes = notes.trim().take(4_000),
            history = incomingHistory,
            chatState = chatState,
            sourceSessionIds = listOf(sourceSessionId).filter(String::isNotBlank),
            excludedMessageKeys = excluded,
            updatedAt = now,
        )
        val savedStory = baseStory?.let { mergeGalleryStories(it, incomingStory).copy(updatedAt = now) }
            ?: incomingStory
        val entry = baseEntry.copy(
            persona = mergePersonaProfiles(baseEntry.persona, persona).copy(id = entryId, updatedAt = now),
            stories = baseEntry.stories.filterNot { it.id == storyId } + savedStory,
            updatedAt = now,
        )
        write(doc.copy(version = 4, entries = doc.entries.filterNot { it.id == entryId } + entry))
        return PersonaGallerySaveOutcome(entry = entry, storyId = storyId)
    }

    @Synchronized
    fun applySuggestions(id: String, suggestions: List<PersonaAppendSuggestion>): PersonaGalleryEntry? {
        if (suggestions.isEmpty()) return readNormalized().entries.firstOrNull { it.id == id }
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return null
        val now = System.currentTimeMillis()
        val merged = current.copy(
            persona = applyPersonaSuggestions(current.persona, suggestions)
                .copy(id = current.id, updatedAt = now),
            updatedAt = now,
        )
        write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) merged else it }))
        return merged
    }

    @Synchronized
    fun updateStoryNotes(id: String, storyId: String, notes: String): Boolean {
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return false
        if (current.stories.none { it.id == storyId }) return false
        val now = System.currentTimeMillis()
        val updated = current.copy(
            stories = current.stories.map { story ->
                if (story.id == storyId) story.copy(notes = notes.trim().take(4_000), updatedAt = now) else story
            },
            updatedAt = now,
        )
        write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
        return true
    }

    @Synchronized
    fun renameStory(id: String, storyId: String, title: String): Boolean {
        val clean = title.trim().take(80)
        if (clean.isBlank()) return false
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return false
        if (current.stories.none { it.id == storyId }) return false
        val now = System.currentTimeMillis()
        val updated = current.copy(
            stories = current.stories.map { story ->
                if (story.id == storyId) story.copy(title = clean, updatedAt = now) else story
            },
            updatedAt = now,
        )
        write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val doc = readNormalized()
        if (doc.entries.none { it.id == id }) return false
        write(doc.copy(version = 4, entries = doc.entries.filterNot { it.id == id }))
        return true
    }

    @Synchronized
    fun deleteStory(id: String, storyId: String): Boolean {
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return false
        if (current.stories.none { it.id == storyId }) return false
        val updated = current.copy(
            stories = current.stories.filterNot { it.id == storyId },
            updatedAt = System.currentTimeMillis(),
        )
        write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
        return true
    }

    @Synchronized
    fun deleteHistoryMessage(id: String, storyId: String, messageKey: String): Boolean {
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return false
        val story = current.stories.firstOrNull { it.id == storyId } ?: return false
        val updatedStory = removeArchivedGalleryMessage(story, messageKey)
            ?.copy(updatedAt = System.currentTimeMillis())
            ?: return false
        val updated = current.copy(
            stories = current.stories.map { if (it.id == storyId) updatedStory else it },
            updatedAt = updatedStory.updatedAt,
        )
        write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
        return true
    }

    private fun fullSharePersona(profile: PersonaProfile): PersonaProfile = profile.copy(
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
        coreMotivations = shareLines(profile.coreMotivations, 12, 240),
        valuePriorities = shareLines(profile.valuePriorities, 12, 240),
        behaviorPatterns = shareLines(profile.behaviorPatterns, 20, 240),
        internalContradictions = shareLines(profile.internalContradictions, 12, 240),
        knowledgeBoundary = shareLines(profile.knowledgeBoundary, 20, 240),
        loreEntries = shareLoreEntries(profile.loreEntries, 80),
        presetId = profile.presetId.trim().take(120),
        hardConstraints = shareLines(profile.hardConstraints, 20, 240),
        exampleDialogues = shareLines(profile.exampleDialogues, 12, 240),
        bannedPhrases = shareLines(profile.bannedPhrases, 30, 240),
        signaturePhrases = shareLines(profile.signaturePhrases, 20, 240),
        corrections = shareLines(profile.corrections, 20, 240),
        updatedAt = 0L,
    )

    private fun compactSharePersona(profile: PersonaProfile): PersonaProfile = profile.copy(
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
        coreMotivations = shareLines(profile.coreMotivations, 2, 60),
        valuePriorities = shareLines(profile.valuePriorities, 2, 60),
        behaviorPatterns = shareLines(profile.behaviorPatterns, 2, 60),
        internalContradictions = shareLines(profile.internalContradictions, 1, 60),
        knowledgeBoundary = shareLines(profile.knowledgeBoundary, 2, 60),
        loreEntries = emptyList(),
        presetId = profile.presetId.trim().take(80),
        hardConstraints = shareLines(profile.hardConstraints, 4, 60),
        exampleDialogues = shareLines(profile.exampleDialogues, 2, 80),
        bannedPhrases = shareLines(profile.bannedPhrases, 6, 30),
        signaturePhrases = shareLines(profile.signaturePhrases, 4, 40),
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
                    keywords = shareLines(entry.keywords, 16, 80),
                    secondaryKeywords = shareLines(entry.secondaryKeywords, 16, 80),
                    priority = entry.priority.coerceIn(0, 100),
                    spoilerLevel = entry.spoilerLevel.coerceIn(0, 3),
                )
            }
            .take(limit)
            .toList()

    private fun shareLines(values: List<String>, limit: Int, maxChars: Int): List<String> =
        values.asSequence()
            .map { it.trim().take(maxChars) }
            .filter(String::isNotBlank)
            .distinct()
            .take(limit)
            .toList()

    private fun readNormalized(): GalleryDocument {
        val raw = read()
        val entries = if (raw.version < 4) {
            compactLegacyDuplicateGalleryEntries(raw.entries)
        } else {
            raw.entries.map(::migrateLegacyEntry)
        }
        val normalized = raw.copy(version = 4, entries = entries)
        if (normalized != raw) write(normalized)
        return normalized
    }

    private fun read(): GalleryDocument =
        durableFile.read(
            defaultValue = ::GalleryDocument,
            decode = { encoded -> json.decodeFromString(GalleryDocument.serializer(), encoded) },
        )

    private fun write(doc: GalleryDocument) {
        val encoded = json.encodeToString(GalleryDocument.serializer(), doc)
        durableFile.write(encoded) { candidate ->
            runCatching {
                json.decodeFromString(GalleryDocument.serializer(), candidate)
            }.isSuccess
        }
    }
}
