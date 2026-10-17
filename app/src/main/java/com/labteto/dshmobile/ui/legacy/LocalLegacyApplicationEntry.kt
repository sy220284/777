package com.labteto.dshmobile.ui.legacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.ui.AppViewModel
import com.labteto.dshmobile.ui.screens.local.LocalHarnessScreen
import com.labteto.dshmobile.ui.screens.local.LocalHarnessViewModel

/**
 * Single explicit compatibility entry, never referenced from the V6 Feature
 * composition root, Host navigation or V6 Scene Pack. This is the only place
 * that still constructs the former aggregate business UI ViewModel.
 */
@Composable
internal fun LocalLegacyApplicationEntry(
    viewModel: AppViewModel,
    requestedLocalSessionId: String?,
    onLocalSessionRequestConsumed: () -> Unit,
    requestedExternalSceneKey: String?,
    onExternalSceneRequestConsumed: () -> Unit,
) {
    val updateInstallStatus by viewModel.updateInstallStatus.collectAsStateWithLifecycle()
    // The application Host derives capabilities from actual Feature Owner composition,
    // never from claims advertised by an installed UI pack.
    val features: LocalHarnessViewModel = hiltViewModel()
    val requestedHostScene by features.uiSceneRequest.collectAsStateWithLifecycle()
    val hostCapabilities = com.labteto.dshmobile.ui.host.LocalUiConnectedCapabilities.from(
        chat = features.chatUiPort,
        work = features.workConversationUiPort,
        drafts = features.conversationDraftUiPort,
        documents = features.uiDocumentUiPort,
        forms = features.uiDocumentFormPort,
        attachments = features.attachmentUiPort,
        transcript = features.messageHistoryUiPort,
        groups = features.chatGroupUiPort,
        team = features.workTeamUiPort,
        workReads = features.workReadUiPort,
        workPolicy = features.workPolicyUiPort,
        shell = features.conversationShellUiPort,
        evidence = features.workEvidenceUiPort,
        gallery = features.personaGalleryUiPort,
        personas = features.personaSelectionUiPort,
        personaTransfer = features.personaTransferUiPort,
        stories = features.personaStoryUiPort,
        galleryDeletion = features.personaGalleryDeletionUiPort,
        gallerySave = features.personaGallerySaveUiPort,
        portraits = features.personaPortraitUiPort,
        authoring = features.personaAuthoringUiPort, currentPersona = features.currentPersonaUiPort,
        files = features.filesUiPort,
        memory = features.memoryUiPort,
        settings = features.settingsUiPort,
        automation = features.automationUiPort,
        media = features.mediaUiPort,
        usage = features.usageUiPort,
        diary = features.diaryUiPort,
    )

    com.labteto.dshmobile.ui.host.LocalApplicationUiHost(
        // V6 always boots through LocalV6StandaloneViewModel. The retired
        // aggregate business ViewModel must never compose a second V6 pack.
        installedPacks = emptyList(),
        availableCapabilities = hostCapabilities,
        requestedSceneKey = requestedExternalSceneKey ?: requestedHostScene,
        notificationSessionId = requestedLocalSessionId,
        notificationSessions = features.sessionUiPort,
        onNotificationSessionProcessed = onLocalSessionRequestConsumed,
        onSceneRequestProcessed = { key, _ ->
            if (requestedExternalSceneKey == key) onExternalSceneRequestConsumed()
            else features.consumeUiSceneRequest(key)
        },
    ) {
        LocalHarnessScreen(
            requestedSessionId = requestedLocalSessionId,
            onSessionRequestConsumed = onLocalSessionRequestConsumed,
            onCheckUpdate = { viewModel.checkForUpdateAndInstall(BuildConfig.VERSION_NAME) },
            updateStatus = updateInstallStatus,
        )
    }
}
