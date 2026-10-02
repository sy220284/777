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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class ChatPersonaGalleryStore internal constructor(
    private val file: File,
    private val json: Json,
) {
    @Inject constructor(@ApplicationContext context: Context, json: Json) :
        this(File(context.filesDir, "local-harness/chat/persona-gallery.json"), json)

    private val documentStore = PersonaGalleryDocumentStore(file, json)
    private val history = PersonaGalleryHistoryCoordinator(
        File(requireNotNull(file.parentFile), "persona-history"),
        json,
    )

    @Synchronized
    fun list(): List<PersonaGalleryEntry> = readNormalized().entries.sortedByDescending { it.updatedAt }

    @Synchronized
    internal fun loadStoryHistory(
        id: String,
        storyId: String,
        limit: Int,
    ): PersonaGalleryHistoryPage {
        val entry = readNormalized().entries.firstOrNull { it.id == id }
            ?: error("人物档案不存在")
        require(entry.stories.any { it.id == storyId }) { "人物故事不存在" }
        return history.page(entry, storyId, limit)
    }

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
    internal fun exportPersonaDocument(
        id: String,
        format: PersonaTransferFormat,
    ): PersonaTransferDocument {
        val entry = readNormalized().entries.firstOrNull { it.id == id }
            ?: error("人物档案不存在")
        return PersonaTransferDocuments.encode(json, history.hydrate(entry), format)
    }

    @Synchronized
    internal fun importPersonaDocument(
        bytes: ByteArray,
        fileName: String? = null,
        mimeType: String? = null,
    ): PersonaGalleryEntry {
        val canonicalJson = PersonaTransferDocuments.decodeToCanonicalJson(
            bytes = bytes,
            fileName = fileName,
            mimeType = mimeType,
        )
        val schema = runCatching {
            json.parseToJsonElement(canonicalJson)
                .jsonObject["schema"]
                ?.jsonPrimitive
                ?.intOrNull
        }.getOrElse { error ->
            throw IllegalArgumentException("人物迁移数据格式不正确", error)
        }
        return when (schema) {
            2 -> importArchivedEntry(
                PersonaTransferDocuments.decodeArchive(json, canonicalJson).entry,
            )
            1, null -> importPersona(canonicalJson)
            else -> throw IllegalArgumentException("暂不支持这个人物迁移版本")
        }
    }

    private fun importArchivedEntry(source: PersonaGalleryEntry): PersonaGalleryEntry {
        val now = System.currentTimeMillis()
        val importedPersona = fullSharePersona(source.persona).copy(updatedAt = now)
        require(isMeaningfulGalleryPersona(importedPersona)) { "人物设定内容不足，无法导入" }

        val seenStoryIds = hashSetOf<String>()
        val importedStories = source.stories.map { raw ->
            val sourceId = raw.id.trim().take(120)
            val storyId = sourceId
                .takeIf { it.isNotBlank() && seenStoryIds.add(it) }
                ?: "story-${UUID.randomUUID()}".also { seenStoryIds.add(it) }
            raw.copy(
                id = storyId,
                title = raw.title.trim().take(160),
                notes = raw.notes.trim().take(4_000),
                history = mergeHistory(emptyList(), raw.history),
                sourceSessionIds = emptyList(),
                excludedMessageKeys = emptyList(),
            )
        }

        val doc = readNormalized()
        val matched = doc.entries.filter {
            samePersonaIdentity(it.persona, importedPersona)
        }.singleOrNull()
        val entryId = matched?.id ?: "gallery-${UUID.randomUUID()}"
        val mergedEntry = if (matched != null) {
            matched.copy(
                persona = mergePersonaProfiles(matched.persona, importedPersona)
                    .copy(id = entryId, updatedAt = now),
                groupChatState = mergeChatState(matched.groupChatState, source.groupChatState),
                stories = mergeLegacyStoryLists(matched.stories, importedStories),
                updatedAt = now,
            )
        } else {
            PersonaGalleryEntry(
                id = entryId,
                persona = importedPersona.copy(id = entryId),
                groupChatState = source.groupChatState,
                stories = importedStories,
                updatedAt = now,
            )
        }
        val entry = history.archiveEntry(mergedEntry)
        documentStore.write(doc.copy(version = 4, entries = doc.entries.filterNot { it.id == entryId } + entry))
        return entry
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
        documentStore.write(doc.copy(version = 4, entries = doc.entries.filterNot { it.id == entryId } + entry))
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
            documentStore.write(doc.copy(version = 4, entries = doc.entries.filterNot { it.id == entryId } + entry))
            return PersonaGallerySaveOutcome(entry = entry, storyId = null)
        }

        val storyId = baseStory?.id ?: "story-${UUID.randomUUID()}"
        val archivedHistory = this.history.merge(entryId, storyId, incomingHistory)
        val incomingStory = PersonaGalleryStory(
            id = storyId,
            title = baseStory?.title?.takeIf(String::isNotBlank)
                ?: defaultStoryTitle(incomingHistory),
            notes = notes.trim().take(4_000),
            history = archivedHistory.messages,
            historyTotalCount = archivedHistory.totalCount,
            historyArchived = true,
            chatState = chatState,
            sourceSessionIds = listOf(sourceSessionId).filter(String::isNotBlank),
            excludedMessageKeys = excluded,
            updatedAt = now,
        )
        val savedStory = (baseStory?.let { mergeGalleryStories(it, incomingStory).copy(updatedAt = now) }
            ?: incomingStory).copy(
                history = archivedHistory.messages,
                historyTotalCount = archivedHistory.totalCount,
                historyArchived = true,
            )
        val entry = baseEntry.copy(
            persona = mergePersonaProfiles(baseEntry.persona, persona).copy(id = entryId, updatedAt = now),
            stories = baseEntry.stories.filterNot { it.id == storyId } + savedStory,
            updatedAt = now,
        )
        documentStore.write(doc.copy(version = 4, entries = doc.entries.filterNot { it.id == entryId } + entry))
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
        documentStore.write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) merged else it }))
        return merged
    }

    @Synchronized
    fun updatePortraitPath(id: String, portraitPath: String): PersonaGalleryEntry? {
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return null
        val clean = portraitPath.trim().take(1_024)
        val now = System.currentTimeMillis()
        val updated = current.copy(
            portraitPath = clean,
            updatedAt = now,
        )
        documentStore.write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
        return updated
    }

    /**
     * Permanently exclude messages removed by an active-chat timeline rewrite.
     *
     * The story state is reset as well because it may summarize facts from the discarded suffix.
     */
    @Synchronized
    fun excludeHistoryMessages(
        id: String,
        storyId: String,
        messageKeys: Collection<String>,
        replacementChatState: ChatCharacterState,
    ): Int {
        val keys = messageKeys.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toCollection(linkedSetOf())
        if (keys.isEmpty()) return 0

        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return 0
        val story = current.stories.firstOrNull { it.id == storyId } ?: return 0
        val now = System.currentTimeMillis()
        val (updatedStory, removed) = history.exclude(
            entryId = id,
            story = story,
            keys = keys,
            replacementChatState = replacementChatState,
            updatedAt = now,
        )
        val updated = current.copy(
            stories = current.stories.map { if (it.id == storyId) updatedStory else it },
            updatedAt = now,
        )
        documentStore.write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
        return removed
    }

    /** Replace, rather than merge, a group state when an old timeline is explicitly rewritten. */
    @Synchronized
    fun replaceGroupChatState(id: String, chatState: ChatCharacterState): PersonaGalleryEntry? {
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return null
        val now = System.currentTimeMillis()
        val updated = current.copy(
            groupChatState = chatState,
            updatedAt = maxOf(current.updatedAt, now),
        )
        documentStore.write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
        return updated
    }

    @Synchronized
    fun updateGroupChatState(id: String, chatState: ChatCharacterState): PersonaGalleryEntry? {
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return null
        val mergedState = mergeChatState(current.groupChatState, chatState)
        val now = maxOf(System.currentTimeMillis(), mergedState.updatedAt)
        val updated = current.copy(
            groupChatState = mergedState,
            updatedAt = maxOf(current.updatedAt, now),
        )
        documentStore.write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
        return updated
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
        documentStore.write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
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
        documentStore.write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val doc = readNormalized()
        if (doc.entries.none { it.id == id }) return false
        documentStore.write(doc.copy(version = 4, entries = doc.entries.filterNot { it.id == id }))
        history.deleteEntry(id)
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
        documentStore.write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
        history.deleteStory(id, storyId)
        return true
    }

    @Synchronized
    fun deleteHistoryMessage(id: String, storyId: String, messageKey: String): Boolean {
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return false
        val story = current.stories.firstOrNull { it.id == storyId } ?: return false
        val updatedStory = history.deleteMessage(
            entryId = id,
            story = story,
            messageKey = messageKey,
            updatedAt = System.currentTimeMillis(),
        ) ?: return false
        val updated = current.copy(
            stories = current.stories.map { if (it.id == storyId) updatedStory else it },
            updatedAt = updatedStory.updatedAt,
        )
        documentStore.write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
        return true
    }

    private fun readNormalized(): GalleryDocument {
        val raw = documentStore.read()
        val migratedEntries = if (raw.version < 4) {
            compactLegacyDuplicateGalleryEntries(raw.entries)
        } else {
            raw.entries.map(::migrateLegacyEntry)
        }
        val entries = migratedEntries.map(history::migrate)
        val normalized = raw.copy(version = 4, entries = entries)
        if (normalized != raw) documentStore.write(normalized)
        return normalized
    }

}
