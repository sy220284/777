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

@Singleton
class ChatPersonaGalleryStore internal constructor(
    private val file: File,
    private val json: Json,
) {
    @Inject constructor(@ApplicationContext context: Context, json: Json) :
        this(File(context.filesDir, "local-harness/chat/persona-gallery-v6.json"), json)

    private val documentStore = PersonaGalleryDocumentStore(file, json)
    private val history = PersonaGalleryHistoryCoordinator(
        File(requireNotNull(file.parentFile), "persona-history-v6"),
        json,
    )
    private val archiveDeletionJournal = PersonaGalleryArchiveDeletionJournal(file, json)

    private var excludedHistoryReconciled = false

    private fun recoverDurableHistoryExclusions(document: GalleryDocument): GalleryDocument {
        if (excludedHistoryReconciled) return document
        val recovered = document.copy(
            entries = document.entries.map { entry ->
                entry.copy(stories = entry.stories.map { story ->
                    if (story.excludedMessageKeys.isEmpty()) return@map story
                    history.pruneExcluded(entry.id, story.id, story.excludedMessageKeys.toSet())
                    val page = history.page(entry, story.id, PersonaGalleryHistoryStore.HOT_GALLERY_HISTORY_MESSAGES)
                    story.copy(history = page.messages, historyTotalCount = page.totalCount)
                })
            },
        )
        if (recovered != document) documentStore.write(recovered)
        excludedHistoryReconciled = true
        return recovered
    }


    @Synchronized
    fun list(): List<PersonaGalleryEntry> = readNormalized().entries.sortedByDescending { it.updatedAt }

    @Synchronized
    internal fun findEntry(id: String): PersonaGalleryEntry? =
        readNormalized().entries.firstOrNull { it.id == id }

    @Synchronized
    internal fun restoreEntry(id: String, previous: PersonaGalleryEntry?) {
        require(previous == null || previous.id == id) { "人物图集回滚编号不一致" }
        val document = readNormalized()
        val restored = document.entries.filterNot { it.id == id }.toMutableList()
        previous?.let(restored::add)
        documentStore.write(document.copy(version = 5, entries = restored))
        excludedHistoryReconciled = false
    }

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
        diaryEntries: List<ChatDiaryEntry> = emptyList(),
    ): PersonaTransferDocument {
        val entry = readNormalized().entries.firstOrNull { it.id == id }
            ?: error("人物档案不存在")
        return PersonaTransferDocuments.encode(json, history.hydrate(entry), format, diaryEntries)
    }

    @Synchronized
    internal fun importPersonaDocument(
        bytes: ByteArray,
        fileName: String? = null,
        mimeType: String? = null,
    ): PersonaGalleryEntry =
        commitPersonaDocumentImport(preparePersonaDocumentImport(bytes, fileName, mimeType))

    @Synchronized
    internal fun preparePersonaDocumentImport(
        bytes: ByteArray,
        fileName: String? = null,
        mimeType: String? = null,
    ): PersonaGalleryPreparedImport {
        val canonicalJson = PersonaTransferDocuments.decodeToCanonicalJson(bytes, fileName, mimeType)
        return when (val decoded = PersonaDocumentCodec.decodeDocumentImport(json, canonicalJson)) {
            is PersonaDocumentImport.Archive -> {
                val current = readNormalized()
                val planned = PersonaGalleryImportPlanner.archive(current, decoded.entry, decoded.diaryEntries)
                planned.copy(
                    rollbackEntry = current.entries
                        .firstOrNull { it.id == planned.entry.id }
                        ?.let(history::hydrate),
                )
            }
            PersonaDocumentImport.Share -> PersonaGalleryPreparedImport.Share(canonicalJson)
        }
    }

    @Synchronized
    internal fun commitPersonaDocumentImport(prepared: PersonaGalleryPreparedImport): PersonaGalleryEntry =
        when (prepared) {
            is PersonaGalleryPreparedImport.Share -> importPersona(prepared.payload)
            is PersonaGalleryPreparedImport.Archive -> commitPreparedArchive(prepared)
        }

    private fun commitPreparedArchive(prepared: PersonaGalleryPreparedImport.Archive): PersonaGalleryEntry {
        check(readNormalized() == prepared.baseDocument) { "人物图集在导入期间发生变化，请重试" }
        return try {
            val entry = history.archiveEntry(prepared.entry)
            val base = prepared.baseDocument
            documentStore.write(base.copy(version = 5, entries = base.entries.filterNot { it.id == entry.id } + entry))
            entry
        } catch (error: Throwable) {
            runCatching {
                history.deleteEntry(prepared.entry.id)
                prepared.rollbackEntry?.let(history::archiveEntry)
            }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching {
                documentStore.write(prepared.baseDocument)
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    @Synchronized
    fun importPersona(payload: String): PersonaGalleryEntry {
        val cleanPayload = payload.trim()
        require(cleanPayload.isNotEmpty() && cleanPayload.length <= MAX_PERSONA_IMPORT_CHARS) {
            "人物分享数据为空或过大"
        }
        val decodedPersona = PersonaDocumentCodec.decodeShare(json, cleanPayload)

        val now = System.currentTimeMillis()
        val imported = fullSharePersona(decodedPersona).copy(updatedAt = now)
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
        documentStore.write(doc.copy(version = 5, entries = doc.entries.filterNot { it.id == entryId } + entry))
        return entry
    }

    @Synchronized
    fun save(
        persona: PersonaProfile,
        sourceSessionId: String,
        history: List<LocalHarnessMessage>,
        chatState: ChatCharacterState,
        notes: String, chatContext: ChatContextState = ChatContextState(),
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
        // An explicitly bound gallery card is an authoritative edited snapshot.
        // Import/name-based matching still uses enrichment merge semantics below.
        val explicitlyEdited = explicit != null
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
            chatState.updatedAt > 0L ||
            chatContext.hasUsefulFacts()
        if (!shouldSaveStory) {
            val entry = baseEntry.copy(
                persona = (if (explicitlyEdited) persona else mergePersonaProfiles(baseEntry.persona, persona))
                    .copy(id = entryId, updatedAt = now),
                updatedAt = now,
            )
            documentStore.write(doc.copy(version = 5, entries = doc.entries.filterNot { it.id == entryId } + entry))
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
            chatState = chatState.canonicalizeLegacyCharacterState().withoutLegacyConversationContext(),
            // The archive's user-edited story stage wins over a stale foreground snapshot.
            chatContext = chatContext.normalized().copy(
                storyStage = baseStory?.chatContext?.storyStage ?: chatContext.storyStage,
            ),
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
            persona = (if (explicitlyEdited) persona else mergePersonaProfiles(baseEntry.persona, persona))
                .copy(id = entryId, updatedAt = now),
            stories = baseEntry.stories.filterNot { it.id == storyId } + savedStory,
            updatedAt = now,
        )
        documentStore.write(doc.copy(version = 5, entries = doc.entries.filterNot { it.id == entryId } + entry))
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
        documentStore.write(doc.copy(version = 5, entries = doc.entries.map { if (it.id == id) merged else it }))
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
        documentStore.write(doc.copy(version = 5, entries = doc.entries.map { if (it.id == id) updated else it }))
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
        // Persist the tombstone before changing physical history: an interruption is recoverable.
        val protectedStory = story.copy(
            excludedMessageKeys = mergePersonaLines(
                story.excludedMessageKeys, keys.toList(), MAX_GALLERY_EXCLUDED_MESSAGE_KEYS,
            ),
            chatState = replacementChatState,
            updatedAt = now,
        )
        val protectedEntry = current.copy(
            stories = current.stories.map { if (it.id == storyId) protectedStory else it },
            updatedAt = now,
        )
        documentStore.write(doc.copy(version = 5, entries = doc.entries.map { if (it.id == id) protectedEntry else it }))
        excludedHistoryReconciled = false
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
        documentStore.write(doc.copy(version = 5, entries = doc.entries.map { if (it.id == id) updated else it }))
        return removed
    }

    /** Replace, rather than merge, a group state when an old timeline is explicitly rewritten. */
    @Synchronized
    fun replaceGroupChatState(id: String, chatState: ChatCharacterState): PersonaGalleryEntry? {
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return null
        val now = System.currentTimeMillis()
        val updated = current.copy(
            groupChatState = chatState.canonicalizeLegacyCharacterState().withoutLegacyConversationContext(),
            updatedAt = maxOf(current.updatedAt, now),
        )
        documentStore.write(doc.copy(version = 5, entries = doc.entries.map { if (it.id == id) updated else it }))
        return updated
    }

    /**
     * One document transaction for a group projection. Session remains the authoritative fact;
     * a failed write leaves all members pending for the existing recovery path.
     */
    @Synchronized
    internal fun updateGroupChatStates(states: List<Pair<String, ChatCharacterState>>): Set<String> {
        if (states.isEmpty()) return emptySet()
        val changes = states.toMap()
        val document = readNormalized()
        val found = linkedSetOf<String>()
        val updated = document.entries.map { entry ->
            val state = changes[entry.id] ?: return@map entry
            found += entry.id
            val merged = mergeChatState(
                entry.groupChatState,
                state.canonicalizeLegacyCharacterState().withoutLegacyConversationContext(),
            )
            entry.copy(
                groupChatState = merged,
                updatedAt = maxOf(entry.updatedAt, System.currentTimeMillis(), merged.updatedAt),
            )
        }
        if (found.isNotEmpty()) {
            documentStore.write(document.copy(version = 5, entries = updated))
        }
        return found
    }

    @Synchronized
    fun updateGroupChatState(id: String, chatState: ChatCharacterState): PersonaGalleryEntry? {
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return null
        val mergedState = mergeChatState(current.groupChatState, chatState.canonicalizeLegacyCharacterState().withoutLegacyConversationContext())
        val now = maxOf(System.currentTimeMillis(), mergedState.updatedAt)
        val updated = current.copy(
            groupChatState = mergedState,
            updatedAt = maxOf(current.updatedAt, now),
        )
        documentStore.write(doc.copy(version = 5, entries = doc.entries.map { if (it.id == id) updated else it }))
        return updated
    }

    @Synchronized
    fun updateStoryDetails(id: String, storyId: String, notes: String, storyStage: String): Boolean {
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return false
        if (current.stories.none { it.id == storyId }) return false
        val now = System.currentTimeMillis()
        val updated = current.copy(
            stories = current.stories.map { story ->
                if (story.id == storyId) story.copy(
                    notes = notes.trim().take(4_000),
                    chatContext = story.chatContext.withStoryStageSelection(storyStage),
                    updatedAt = now,
                ) else story
            },
            updatedAt = now,
        )
        documentStore.write(doc.copy(version = 5, entries = doc.entries.map { if (it.id == id) updated else it }))
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
        documentStore.write(doc.copy(version = 5, entries = doc.entries.map { if (it.id == id) updated else it }))
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val doc = readNormalized()
        if (doc.entries.none { it.id == id }) return false
        val updated = doc.copy(version = 5, entries = doc.entries.filterNot { it.id == id })
        archiveDeletionJournal.queue(id)
        documentStore.write(updated)
        archiveDeletionJournal.drain(updated, history)
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
        val next = doc.copy(version = 5, entries = doc.entries.map { if (it.id == id) updated else it })
        archiveDeletionJournal.queue(id, storyId)
        documentStore.write(next)
        archiveDeletionJournal.drain(next, history)
        return true
    }

    @Synchronized
    fun deleteHistoryMessage(id: String, storyId: String, messageKey: String): Boolean {
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return false
        val story = current.stories.firstOrNull { it.id == storyId } ?: return false
        if (!history.containsMessage(id, storyId, messageKey)) return false
        val now = System.currentTimeMillis()
        val protectedStory = story.copy(
            excludedMessageKeys = mergePersonaLines(
                story.excludedMessageKeys, listOf(messageKey), MAX_GALLERY_EXCLUDED_MESSAGE_KEYS,
            ),
            updatedAt = now,
        )
        val protectedEntry = current.copy(
            stories = current.stories.map { if (it.id == storyId) protectedStory else it },
            updatedAt = now,
        )
        documentStore.write(doc.copy(version = 5, entries = doc.entries.map { if (it.id == id) protectedEntry else it }))
        excludedHistoryReconciled = false
        val updatedStory = history.deleteMessage(
            entryId = id,
            story = protectedStory,
            messageKey = messageKey,
            updatedAt = now,
        ) ?: protectedStory
        val updated = current.copy(
            stories = current.stories.map { if (it.id == storyId) updatedStory else it },
            updatedAt = updatedStory.updatedAt,
        )
        documentStore.write(doc.copy(version = 5, entries = doc.entries.map { if (it.id == id) updated else it }))
        return true
    }

    private fun readNormalized(): GalleryDocument {
        val document = documentStore.read()
        require(document.version == 5) {
            "人物图集版本不受支持"
        }
        val entries = document.entries.map(history::migrate)
        val normalized = document.copy(entries = entries)
        if (normalized != document) documentStore.write(normalized)
        val recovered = recoverDurableHistoryExclusions(normalized)
        archiveDeletionJournal.drain(recovered, history)
        return recovered
    }



}
