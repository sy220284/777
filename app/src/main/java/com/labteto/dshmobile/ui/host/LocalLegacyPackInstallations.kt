package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.ui.screens.local.LocalHarnessViewModel
import com.labteto.dshmobile.uipack.v6.V6ApplicationScenePack
import com.labteto.dshmobile.uipack.v6.V6ConversationPorts
import com.labteto.dshmobile.uipack.v6.V6SecondaryPorts
import com.labteto.dshmobile.uihost.UiApplicationScenePack

/**
 * Retired Shell -> V6 composition bridge. Kept in its own compatibility file;
 * the production Host and V6-only boot load no Legacy ViewModel class.
 * Remove this file when the Legacy entry is deleted.
 */
internal object LocalApplicationPackInstallations {
    fun fromOwners(owner: LocalHarnessViewModel,
        updateStatus: kotlinx.coroutines.flow.StateFlow<String?>? = null,
        checkForUpdate: (() -> Unit)? = null,
    ): List<UiApplicationScenePack> {
        val session = LocalV6SessionBinding(owner.sessionUiPort)
        return listOf(
            // Migrating V6 has only actual settings and conversation renderers; Host denies it
            // until every required Scene exists, so current users stay on legacy stable V6.
            V6ApplicationScenePack(
                owner.hostPreferencesUiPort,
                owner.settingsUiPort,
                V6ConversationPorts(
                    chat = owner.chatUiPort, work = owner.workConversationUiPort,
                    drafts = owner.conversationDraftUiPort,
                    reading = owner.conversationReadingUiPort,
                    attachments = owner.attachmentUiPort, history = owner.messageHistoryUiPort,
                    documents = owner.uiDocumentUiPort, forms = owner.uiDocumentFormPort,
                    media = owner.mediaUiPort, evidence = owner.workEvidenceUiPort,
                    gallery = owner.personaGalleryUiPort,
                    selection = owner.personaSelectionUiPort,
                    groups = owner.chatGroupUiPort,
                    stories = owner.personaStoryUiPort,
                    galleryDeletion = owner.personaGalleryDeletionUiPort,
                    personaAuthoring = owner.personaAuthoringUiPort,
                    workReads = owner.workReadUiPort,
                    workPolicy = owner.workPolicyUiPort,
                    workTeam = owner.workTeamUiPort,
                    replySuggestions = owner.replySuggestionsUiPort,
                    files = owner.filesUiPort,
                    workMode = session::isWork,
                    workModeUpdates = session.workModeUpdates,
                ),
                usageOwner = owner.usageUiPort,
                updateStatus = updateStatus,
                checkForUpdate = checkForUpdate,
                secondary = V6SecondaryPorts(
                    tools = owner.toolsUiPort, files = owner.filesUiPort,
                    media = owner.mediaUiPort, settings = owner.settingsUiPort,
                    sessionId = session::sessionId,
                    automation = owner.automationUiPort, sessions = owner.sessionUiPort,
                    transcript = owner.messageHistoryUiPort,
                    memory = owner.memoryUiPort, diary = owner.diaryUiPort,
                    authoring = owner.personaAuthoringUiPort,
                    currentPersona = owner.currentPersonaUiPort,
                    replySuggestions = owner.replySuggestionsUiPort,
                    gallery = owner.personaGalleryUiPort,
                    selection = owner.personaSelectionUiPort,
                    portraits = owner.personaPortraitUiPort,
                    modelPerformance = LocalModelPerformanceUiOwner,
                    pricing = owner.memoryUiPort as? com.labteto.dshmobile.uicontract.PricingUiPort,
                ),
            ),
        )
    }
}
