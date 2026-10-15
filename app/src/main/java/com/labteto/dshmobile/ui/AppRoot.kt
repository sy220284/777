package com.labteto.dshmobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.ui.screens.local.LocalHarnessScreen
import com.labteto.dshmobile.ui.screens.local.LocalHarnessViewModel
import com.labteto.dshmobile.ui.host.LocalConversationPackGate
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference

/** Only the local product shell owns application navigation. */
@Composable
fun AppRoot(
    viewModel: AppViewModel = hiltViewModel(),
    requestedLocalSessionId: String? = null,
    onLocalSessionRequestConsumed: () -> Unit = {},
    openConversationUiSlice: Boolean = false,
    onConversationUiSliceRequestConsumed: () -> Unit = {},
) {
    var sliceOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openConversationUiSlice) {
        if (BuildConfig.DEBUG && openConversationUiSlice) {
            sliceOpen = true
            onConversationUiSliceRequestConsumed()
        }
    }
    if (sliceOpen) {
        com.labteto.dshmobile.ui.host.LocalConversationSliceHost(
            requestedLocalSessionId, onLocalSessionRequestConsumed, onClose = { sliceOpen = false },
        )
        return
    }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val themePreference = remember(settings.themePreference) {
        runCatching { ThemePreference.valueOf(settings.themePreference.uppercase()) }
            .getOrDefault(ThemePreference.SYSTEM)
    }
    val updateInstallStatus by viewModel.updateInstallStatus.collectAsStateWithLifecycle()

    // The application Host derives capabilities from actual Feature Owner composition,
    // never from claims advertised by an installed UI pack.
    val features: LocalHarnessViewModel = hiltViewModel()
    val requestedHostScene by features.uiSceneRequest.collectAsStateWithLifecycle()
    val hostCapabilities = LocalConversationPackGate.capabilities(
        chat = features.chatUiPort,
        work = features.workConversationUiPort,
        drafts = features.conversationDraftUiPort,
        documents = features.nextUiDocumentUiPort,
        forms = features.uiDocumentFormPort,
        attachments = features.attachmentUiPort,
        transcript = features.messageHistoryUiPort,
        groups = features.chatGroupUiPort,
        team = features.workTeamUiPort,
        shell = features.conversationShellUiPort,
        evidence = features.workEvidenceUiPort,
        gallery = features.personaGalleryUiPort,
        personas = features.personaSelectionUiPort,
        personaTransfer = features.personaTransferUiPort,
        stories = features.personaStoryUiPort,
        galleryDeletion = features.personaGalleryDeletionUiPort,
        gallerySave = features.personaGallerySaveUiPort,
        portraits = features.personaPortraitUiPort,
    )

    DshTheme(
        preference = themePreference,
        accentKey = settings.accentTheme,
        backgroundPath = settings.backgroundImagePath,
        backgroundAdaptiveContrast = settings.backgroundAdaptiveContrast,
        textScale = settings.textScale,
        textWeightAdjustment = settings.textWeightAdjustment,
        wallpaperSurfaceTransparency = settings.wallpaperSurfaceTransparency,
        ambientGlow = settings.ambientGlow,
        componentEmphasis = settings.componentEmphasis,
        motionDurationScale = settings.motionDurationScale,
    ) {
        com.labteto.dshmobile.ui.host.LocalApplicationUiHost(
            installedPacks = com.labteto.dshmobile.ui.host.LocalApplicationPackInstallations.all,
            availableCapabilities = hostCapabilities,
            requestedSceneKey = requestedHostScene,
            onSceneRequestProcessed = { key, _ -> features.consumeUiSceneRequest(key) },
        ) {
            LocalHarnessScreen(
                requestedSessionId = requestedLocalSessionId,
                onSessionRequestConsumed = onLocalSessionRequestConsumed,
                onCheckUpdate = { viewModel.checkForUpdateAndInstall(BuildConfig.VERSION_NAME) },
                updateStatus = updateInstallStatus,
            )
        }
    }
}
