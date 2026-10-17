package com.labteto.dshmobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.labteto.dshmobile.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.ui.screens.local.LocalHarnessScreen
import com.labteto.dshmobile.ui.screens.local.LocalHarnessViewModel
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference

/** Only the local product shell owns application navigation. */
@Composable
fun AppRoot(
    viewModel: AppViewModel = hiltViewModel(),
    requestedLocalSessionId: String? = null,
    onLocalSessionRequestConsumed: () -> Unit = {},
    requestedExternalSceneKey: String? = null,
    onExternalSceneRequestConsumed: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val themePreference = remember(settings.themePreference) {
        runCatching { ThemePreference.valueOf(settings.themePreference.uppercase()) }
            .getOrDefault(ThemePreference.SYSTEM)
    }
    val updateInstallStatus by viewModel.updateInstallStatus.collectAsStateWithLifecycle()

    // The selected application package is observed before creating the legacy VM.
    // This is crucial: a genuinely independent V6 must be runnable without
    // ever constructing LocalHarnessViewModel or LocalHarnessScreen.
    val application = androidx.compose.ui.platform.LocalContext.current
    val selection = remember(application) {
        application.getSharedPreferences("ui_application_pack", android.content.Context.MODE_PRIVATE)
    }
    var standaloneSelected by remember(selection) {
        androidx.compose.runtime.mutableStateOf(
            com.labteto.dshmobile.ui.host.isIndependentV6Selected(
                selection.getString("selected_pack", null))
        )
    }
    androidx.compose.runtime.DisposableEffect(selection) {
        val observer = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "selected_pack")
                standaloneSelected = com.labteto.dshmobile.ui.host.isIndependentV6Selected(prefs.getString(key, null))
        }
        selection.registerOnSharedPreferenceChangeListener(observer)
        onDispose { selection.unregisterOnSharedPreferenceChangeListener(observer) }
    }

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
        if (standaloneSelected) {
            // Owner-only boot path: no LocalHarnessViewModel, no LocalHarnessScreen,
            // and no dispatch into legacy business callbacks on this branch.
            val owner: com.labteto.dshmobile.ui.host.LocalV6StandaloneViewModel = hiltViewModel()
            val requestedV6Scene by owner.sceneRequest.collectAsStateWithLifecycle()
            com.labteto.dshmobile.ui.host.LocalApplicationUiHost(
                installedPacks = remember(owner, viewModel) {
                    listOf(owner.pack(viewModel.updateInstallStatus) {
                        viewModel.checkForUpdateAndInstall(BuildConfig.VERSION_NAME)
                    })
                },
                availableCapabilities = owner.connectedCapabilities,
                requestedSceneKey = requestedExternalSceneKey ?: requestedV6Scene,
                notificationSessionId = requestedLocalSessionId,
                notificationSessions = owner.sessions,
                onNotificationSessionProcessed = onLocalSessionRequestConsumed,
                onSceneRequestProcessed = { key, _ ->
                    if (requestedExternalSceneKey == key) onExternalSceneRequestConsumed()
                    else owner.consumeSceneRequest(key)
                },
            ) {
                // Fail closed if the selected pack becomes unavailable mid-frame.
                // The user must explicitly select legacy in Host settings to leave V6.
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(R.string.v6_independent_host_unavailable))
            }
        } else {
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
            installedPacks = remember(features, viewModel) {
                com.labteto.dshmobile.ui.host.LocalApplicationPackInstallations.fromOwners(
                    features,
                    updateStatus = viewModel.updateInstallStatus,
                    checkForUpdate = {
                        viewModel.checkForUpdateAndInstall(BuildConfig.VERSION_NAME)
                    },
                )
            },
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
    }
}
