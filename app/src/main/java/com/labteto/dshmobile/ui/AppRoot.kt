package com.labteto.dshmobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.labteto.dshmobile.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.ui.legacy.LocalLegacyApplicationEntry
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

    com.labteto.dshmobile.uihost.ProvideUiHostSystemOperationOwner {
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
                // Compatibility code is loaded only on explicit legacy selection;
                // V6 AppRoot no longer directly references its ViewModel/screen.
                LocalLegacyApplicationEntry(
                    viewModel = viewModel,
                    requestedLocalSessionId = requestedLocalSessionId,
                    onLocalSessionRequestConsumed = onLocalSessionRequestConsumed,
                    requestedExternalSceneKey = requestedExternalSceneKey,
                    onExternalSceneRequestConsumed = onExternalSceneRequestConsumed,
                )
            }
        }
    }
}
