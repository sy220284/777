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

    DshTheme(
        preference = themePreference,
        accentKey = settings.accentTheme,
        backgroundPath = settings.backgroundImagePath,
        backgroundAdaptiveContrast = settings.backgroundAdaptiveContrast,
        textScale = settings.textScale,
        textWeightAdjustment = settings.textWeightAdjustment,
        wallpaperSurfaceTransparency = settings.wallpaperSurfaceTransparency,
    ) {
        LocalHarnessScreen(
            requestedSessionId = requestedLocalSessionId,
            onSessionRequestConsumed = onLocalSessionRequestConsumed,
            onCheckUpdate = { viewModel.checkForUpdateAndInstall(BuildConfig.VERSION_NAME) },
            updateStatus = updateInstallStatus,
        )
    }
}
