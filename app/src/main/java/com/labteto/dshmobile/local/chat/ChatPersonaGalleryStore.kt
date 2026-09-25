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
    val storyId: String,
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
            title = "主线故事",
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
                stories = canonical.stories + entry.stories.map { story ->
                    if (canonical.stories.any { it.id == story.id }) {
                        story.copy(id = "story-${UUID.randomUUID()}")
                    } else story
                },
                updatedAt = maxOf(canonical.updatedAt, entry.updatedAt),
            )
        }
    }
    return compacted.sortedByDescending { it.updatedAt }
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
    persona.hardConstraints.joinToString("\u0000"),
    persona.exampleDialogues.joinToString("\u0000"),
    persona.bannedPhrases.joinToString("\u0000"),
    persona.signaturePhrases.joinToString("\u0000"),
    persona.corrections.joinToString("\u0000"),
).joinToString("\u0001") { normalizePersonaText(it) }

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
        val storyId = baseStory?.id ?: "story-${UUID.randomUUID()}"
        val excluded = baseStory?.excludedMessageKeys.orEmpty()
        val incomingHistory = history
            .filter { it.role == "user" || it.role == "assistant" }
            .filterNot { galleryMessageArchiveKey(it) in excluded }
        val incomingStory = PersonaGalleryStory(
            id = storyId,
            title = baseStory?.title?.takeIf(String::isNotBlank)
                ?: "故事 ${baseEntry.stories.size + 1}",
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
