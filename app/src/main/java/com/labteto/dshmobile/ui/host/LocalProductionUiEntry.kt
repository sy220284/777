package com.labteto.dshmobile.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.ui.AppViewModel

/**
 * The application root never selects a UI brand or renders a design system.
 * This is the one production installation point for compatible complete UI packs.
 * Each pack renders its own visual frame from neutral Host preferences.
 * This entry passes only actual Feature Owners and system callbacks.
 */
@Composable
internal fun LocalProductionUiEntry(
    appViewModel: AppViewModel,
    requestedLocalSessionId: String?,
    onLocalSessionRequestConsumed: () -> Unit,
    requestedExternalSceneKey: String?,
    onExternalSceneRequestConsumed: () -> Unit,
) {
    val owner: LocalV6StandaloneViewModel = hiltViewModel()
    val requestedV6Scene by owner.sceneRequest.collectAsStateWithLifecycle()
    val installedPacks = remember(owner, appViewModel) {
        listOf(owner.pack(
            updateStatus = appViewModel.updateInstallStatus,
            checkForUpdate = {
                appViewModel.checkForUpdateAndInstall(BuildConfig.VERSION_NAME)
            },
        ))
    }
    LocalApplicationUiHost(
        installedPacks = installedPacks,
        availableCapabilities = owner.connectedCapabilities,
        requestedSceneKey = requestedExternalSceneKey ?: requestedV6Scene,
        notificationSessionId = requestedLocalSessionId,
        notificationSessions = owner.sessions,
        onNotificationSessionProcessed = onLocalSessionRequestConsumed,
        onSceneRequestProcessed = { key, _ ->
            if (requestedExternalSceneKey == key) onExternalSceneRequestConsumed()
            else owner.consumeSceneRequest(key)
        },
    )
}

