package com.labteto.dshmobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.ui.host.LocalApplicationUiHost
import com.labteto.dshmobile.ui.host.LocalEncryptedUiDraftStore
import com.labteto.dshmobile.ui.host.LocalV6StandaloneViewModel
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.labteto.dshmobile.uihost.LocalUiPrivateTextCipher
import com.labteto.dshmobile.uihost.ProvideUiHostSystemOperationOwner

/** One Feature Port composition and one Host. Retired Shell callbacks have no application entry. */
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
    val context = LocalContext.current
    val privateText = remember(context) { LocalEncryptedUiDraftStore(context) }
    CompositionLocalProvider(LocalUiPrivateTextCipher provides privateText) {
        ProvideUiHostSystemOperationOwner {
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
                val owner: LocalV6StandaloneViewModel = hiltViewModel()
                val requestedV6Scene by owner.sceneRequest.collectAsStateWithLifecycle()
                LocalApplicationUiHost(
                    installedPacks = remember(owner, viewModel) {
                        listOf(owner.pack(viewModel.updateInstallStatus) {
                            viewModel.checkForUpdateAndInstall(BuildConfig.VERSION_NAME)
                        })
                    },
                    // Both builds admit only capabilities backed by the actual Feature Ports.
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
        }
    }
}
