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

    private val durableFile = RecoveringChatDocumentFile(file)
    private val historyStore = PersonaGalleryHistoryStore(
        File(requireNotNull(file.parentFile), "persona-history"),
        json,
    )

    private val backupFile = File(file.parentFile, "${file.name}.bak")
    private var cachedDocument: GalleryDocument? = null
    private var cachedStamp: DocumentStamp? = null

    @Synchronized
    fun list(): List<PersonaGalleryEntry> = readNormalized().entries.sortedByDescending { it.updatedAt }

    @Synchronized
    fun loadStoryHistory(
        id: String,
        storyId: String,
        limit: Int,
    ): PersonaGalleryHistoryPage {
        val entry = readNormalized().entries.firstOrNull { it.id == id }
            ?: error("人物档案不存在")
        require(entry.stories.any { it.id == storyId }) { "人物故事不存在" }
        return historyStore.tail(id, storyId, limit)
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
        val hydrated = entry.copy(
            stories = entry.stories.map { story ->
                story.copy(
                    history = historyStore.all(entry.id, story.id),
                    historyTotalCount = story.historyTotalCount,
                )
            },
        )
        return PersonaTransferDocuments.encode(json, hydrated, format)
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
        val entry = if (matched != null) {
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
        write(doc.copy(version = 4, entries = doc.entries.filterNot { it.id == entryId } + entry))
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
    fun updatePortraitPath(id: String, portraitPath: String): PersonaGalleryEntry? {
        val doc = readNormalized()
        val current = doc.entries.firstOrNull { it.id == id } ?: return null
        val clean = portraitPath.trim().take(1_024)
        val now = System.currentTimeMillis()
        val updated = current.copy(
            portraitPath = clean,
            updatedAt = now,
        )
        write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
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
        val removed = story.history.count { galleryMessageArchiveKey(it) in keys }
        val now = System.currentTimeMillis()
        val updatedStory = story.copy(
            history = story.history.filterNot { galleryMessageArchiveKey(it) in keys },
            excludedMessageKeys = mergePersonaLines(
                story.excludedMessageKeys,
                keys.toList(),
                MAX_GALLERY_EXCLUDED_MESSAGE_KEYS,
            ),
            chatState = replacementChatState,
            updatedAt = now,
        )
        val updated = current.copy(
            stories = current.stories.map { if (it.id == storyId) updatedStory else it },
            updatedAt = now,
        )
        write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
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
        write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
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
        write(doc.copy(version = 4, entries = doc.entries.map { if (it.id == id) updated else it }))
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

    private fun read(): GalleryDocument {
        val stamp = documentStamp()
        cachedDocument?.takeIf { cachedStamp == stamp }?.let { return it }
        val document = durableFile.read(
            defaultValue = ::GalleryDocument,
            decode = { encoded -> json.decodeFromString(GalleryDocument.serializer(), encoded) },
        )
        cachedDocument = document
        cachedStamp = documentStamp()
        return document
    }

    private fun write(doc: GalleryDocument) {
        val encoded = json.encodeToString(GalleryDocument.serializer(), doc)
        durableFile.write(encoded) { candidate ->
            runCatching {
                json.decodeFromString(GalleryDocument.serializer(), candidate)
            }.isSuccess
        }
        cachedDocument = doc
        cachedStamp = documentStamp()
    }

    private fun documentStamp(): DocumentStamp = DocumentStamp(
        primaryModified = file.takeIf(File::isFile)?.lastModified() ?: -1L,
        primaryLength = file.takeIf(File::isFile)?.length() ?: -1L,
        backupModified = backupFile.takeIf(File::isFile)?.lastModified() ?: -1L,
        backupLength = backupFile.takeIf(File::isFile)?.length() ?: -1L,
    )

    private data class DocumentStamp(
        val primaryModified: Long,
        val primaryLength: Long,
        val backupModified: Long,
        val backupLength: Long,
    )
}
