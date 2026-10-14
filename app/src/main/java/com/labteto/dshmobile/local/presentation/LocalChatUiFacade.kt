package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.visibleStoryStages
import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
import com.labteto.dshmobile.local.chat.PersonaAppendSuggestion
import com.labteto.dshmobile.local.chat.PersonaAutoFillService
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaGallerySaveOutcome
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaInspectionService
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import javax.inject.Inject
import javax.inject.Singleton

/** Chat-owned UI facade. UI consumes domain DTOs/actions without retaining Chat stores/services. */
@Singleton
class LocalChatUiFacade @Inject constructor(
    private val gallery: ChatPersonaGalleryStore,
    private val autoFill: PersonaAutoFillService,
    private val inspection: PersonaInspectionService,
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
        chatContext: ChatContextState? = null,
    ): PersonaInspectionResult = inspection.inspect(
        model, baseUrl, profileId, persona, messages,
        chatContext?.storyStage.orEmpty(), chatContext?.visibleStoryStages().orEmpty(),
    )

}
