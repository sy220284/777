package com.labteto.dshmobile.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.labteto.dshmobile.ui.screens.local.LocalHarnessScreen
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference

/** Only the local product shell owns application navigation. */
@Composable
fun AppRoot(
    viewModel: AppViewModel = hiltViewModel(),
    requestedLocalSessionId: String? = null,
    onLocalSessionRequestConsumed: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val themePreference = remember(settings.themePreference) {
        runCatching { ThemePreference.valueOf(settings.themePreference.uppercase()) }
            .getOrDefault(ThemePreference.SYSTEM)
    }
    val updateInstallStatus by viewModel.updateInstallStatus.collectAsStateWithLifecycle()
    val updateCandidate by viewModel.updateCandidate.collectAsStateWithLifecycle()
    val downloadingUpdate by viewModel.downloadingUpdate.collectAsStateWithLifecycle()

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
        LocalHarnessScreen(
            requestedSessionId = requestedLocalSessionId,
            onSessionRequestConsumed = onLocalSessionRequestConsumed,
            onCheckUpdate = { viewModel.checkForUpdate(BuildConfig.VERSION_NAME) },
            updateStatus = updateInstallStatus,
        )
        if (downloadingUpdate) SyDialog(
            title = stringResource(R.string.update_downloading_title),
            onDismiss = viewModel::cancelUpdateDownload,
            dismissOnScrimTap = false,
        ) {
            updateInstallStatus?.let { Text(it) }
            SyButton(stringResource(R.string.common_cancel), onClick = viewModel::cancelUpdateDownload)
        }
        updateCandidate?.let { update ->
            val isDelta = update.patchChain.isNotEmpty()
            val bytes = if (isDelta) update.patchChain.sumOf { it.size } else update.apkSize
            SyDialog(
                title = stringResource(R.string.update_confirm_title, update.version),
                onDismiss = viewModel::dismissUpdate,
            ) {
                Text(stringResource(if (isDelta) R.string.update_package_delta else R.string.update_package_full))
                if (bytes != null) Text(stringResource(R.string.update_package_size, bytes / (1024.0 * 1024.0)))
                Row {
                    SyButton(stringResource(R.string.common_cancel), onClick = viewModel::dismissUpdate,
                        variant = SyButtonVariant.Ghost)
                    SyButton(stringResource(R.string.update_confirm_download), onClick = viewModel::confirmUpdate)
                }
            }
        }
    }
}
