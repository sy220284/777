package com.labteto.dshmobile.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.ui.AppViewModel
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference

/**
 * The application root never selects a UI brand or renders a design system.
 * This is the one production installation point for compatible complete UI packs.
 * The V6 visual bridge is scoped to V6 only; another pack does not inherit its
 * palette, wallpaper, fonts or motion through the Host.
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
            visualFrame = { content -> V6VisualBridge(appViewModel, content) },
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

/**
 * Existing V6 appearance is preserved byte-for-byte at its source. The visual
 * wrapper now runs only when this pack renders, never around the neutral Host.
 * Follow-up relocation of DshTheme/AppBackground/typography into ui-pack-v6
 * can replace this bridge without changing AppRoot or Host.
 */
@Composable
private fun V6VisualBridge(
    appViewModel: AppViewModel,
    content: @Composable () -> Unit,
) {
    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    val themePreference = remember(settings.themePreference) {
        runCatching { ThemePreference.valueOf(settings.themePreference.uppercase()) }
            .getOrDefault(ThemePreference.SYSTEM)
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
        content = content,
    )
}
