package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatDiaryEntry
import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
import com.labteto.dshmobile.local.chat.GroupAnnouncementService
import com.labteto.dshmobile.local.chat.LocalGroupChatMember
import com.labteto.dshmobile.local.chat.PersonaAppendSuggestion
import com.labteto.dshmobile.local.chat.PersonaAutoFillService
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaGalleryPreparedImport
import com.labteto.dshmobile.local.chat.PersonaGallerySaveOutcome
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaInspectionService
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.PersonaTransferDocument
import com.labteto.dshmobile.local.chat.PersonaTransferFormat
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import javax.inject.Inject
import javax.inject.Singleton

/** Chat-owned UI facade. UI consumes domain DTOs/actions without retaining Chat stores/services. */
@Singleton
class LocalChatUiFacade @Inject constructor(
    private val gallery: ChatPersonaGalleryStore,
    private val autoFill: PersonaAutoFillService,
    private val inspection: PersonaInspectionService,
    private val announcement: GroupAnnouncementService,
) {
    internal fun galleryEntries(): List<PersonaGalleryEntry> = gallery.list()

    internal fun saveGallery(
        persona: PersonaProfile,
        sourceSessionId: String,
        history: List<LocalHarnessMessage>,
        chatState: ChatCharacterState,
        notes: String,
        chatContext: ChatContextState = ChatContextState(),
        existingId: String? = null,
        existingStoryId: String? = null,
        forceNewStory: Boolean = false,
    ): PersonaGallerySaveOutcome = gallery.save(
        persona, sourceSessionId, history, chatState, notes, chatContext,
        existingId, existingStoryId, forceNewStory,
    )

    internal fun applyGallerySuggestions(id: String, suggestions: List<PersonaAppendSuggestion>) =
        gallery.applySuggestions(id, suggestions)
    internal fun updateGalleryPortrait(id: String, path: String) = gallery.updatePortraitPath(id, path)
    internal fun updateStoryDetails(id: String, storyId: String, notes: String, storyStage: String) =
        gallery.updateStoryDetails(id, storyId, notes, storyStage)
    internal fun renameStory(id: String, storyId: String, title: String) = gallery.renameStory(id, storyId, title)
    internal fun deleteGallery(id: String) = gallery.delete(id)
    internal fun deleteStory(id: String, storyId: String) = gallery.deleteStory(id, storyId)
    internal fun deleteHistoryMessage(id: String, storyId: String, messageKey: String) =
        gallery.deleteHistoryMessage(id, storyId, messageKey)

    internal fun exportPersonaDocument(id: String, format: PersonaTransferFormat, diaryEntries: List<ChatDiaryEntry>): PersonaTransferDocument =
        gallery.exportPersonaDocument(id, format, diaryEntries)
    internal fun preparePersonaDocumentImport(bytes: ByteArray, fileName: String?, mimeType: String?): PersonaGalleryPreparedImport =
        gallery.preparePersonaDocumentImport(bytes, fileName, mimeType)
    internal fun commitPersonaDocumentImport(prepared: PersonaGalleryPreparedImport): PersonaGalleryEntry =
        gallery.commitPersonaDocumentImport(prepared)

    internal suspend fun autoFillPersona(
        model: String,
        baseUrl: String,
        profileId: String?,
        current: PersonaProfile,
        recentMessages: List<LocalHarnessMessage>,
        description: String,
    ): PersonaProfile = autoFill.generate(model, baseUrl, profileId, current, recentMessages, description)

    internal suspend fun inspectPersona(
        model: String,
        baseUrl: String,
        profileId: String?,
        persona: PersonaProfile,
        messages: List<LocalHarnessMessage>,
        storyStage: String = "",
        unlockedStages: Collection<String> = emptyList(),
    ): PersonaInspectionResult = inspection.inspect(
        model, baseUrl, profileId, persona, messages, storyStage, unlockedStages,
    )

    internal suspend fun generateGroupAnnouncement(
        model: String,
        baseUrl: String,
        profileId: String?,
        members: List<LocalGroupChatMember>,
        direction: String,
        current: String,
    ): String = announcement.generate(model, baseUrl, profileId, members, direction, current)
}
