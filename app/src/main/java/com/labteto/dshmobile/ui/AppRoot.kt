package com.labteto.dshmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.screens.local.LocalHarnessScreen
import com.labteto.dshmobile.ui.screens.main.MainScreen
import com.labteto.dshmobile.ui.screens.pair.PairScreen
import com.labteto.dshmobile.ui.screens.settings.SettingsScreen
import com.labteto.dshmobile.ui.screens.tasks.TasksScreen
import com.labteto.dshmobile.ui.screens.tools.ToolsScreen
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference

/** Application root: theme + locale-aware shell, connect vs. main routing. */
@Composable
fun AppRoot(
    viewModel: AppViewModel = hiltViewModel(),
    requestedSessionId: String? = null,
    onSessionRequestConsumed: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val themePreference = remember(settings.themePreference) {
        runCatching { ThemePreference.valueOf(settings.themePreference.uppercase()) }
            .getOrDefault(ThemePreference.SYSTEM)
    }

    val updateInstallStatus by viewModel.updateInstallStatus.collectAsStateWithLifecycle()

    DshTheme(
        preference = themePreference,
        backgroundPath = settings.backgroundImagePath,
    ) {
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var utilitySurface by rememberSaveable { mutableStateOf<String?>(null) }
        // Local Harness is always the product home. Remote control has exactly one transport:
        // a paired relay. Opening it lands on relay pairing first; a successful pair connects and
        // carries the user into the remote session, while Back returns to the local home.
        var showPair by rememberSaveable { mutableStateOf(false) }
        var autoScanPair by rememberSaveable { mutableStateOf(false) }
        var relayClaimed by rememberSaveable { mutableStateOf(false) }
        var surface by rememberSaveable { mutableStateOf("local") }
        val showMain = connection.phase == ConnectionPhase.CONNECTED ||
            (connection.phase == ConnectionPhase.RECONNECTING && connection.hasConnected)
        val selectedRemoteMatches = surface == "remote" && connection.host != null
        LaunchedEffect(requestedSessionId) {
            if (!requestedSessionId.isNullOrBlank()) viewModel.prepareNotificationNavigation()
        }
        LaunchedEffect(requestedSessionId, connection.phase) {
            val target = requestedSessionId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
            showSettings = false
            utilitySurface = null
            showPair = false
            surface = "remote"
            relayClaimed = true
            if (connection.phase == ConnectionPhase.CONNECTED && viewModel.openNotificationSession(target)) {
                onSessionRequestConsumed()
            }
        }
        when {
            utilitySurface == "tasks" -> TasksScreen(
                onClose = { utilitySurface = null },
            )
            utilitySurface == "tools" -> ToolsScreen(
                onClose = { utilitySurface = null },
            )
            showSettings -> SettingsScreen(
                onClose = { showSettings = false },
                updateStatus = updateInstallStatus,
                onCheckUpdate = {
                    viewModel.checkForUpdateAndInstall(BuildConfig.VERSION_NAME)
                },
            )
            showPair -> PairScreen(
                autoScanOnOpen = autoScanPair,
                onClose = {
                    showPair = false
                    autoScanPair = false
                    relayClaimed = false
                    surface = "local"
                },
                onPaired = {
                    showPair = false
                    autoScanPair = false
                    relayClaimed = true
                    surface = "remote"
                },
            )
            surface == "local" -> LocalHarnessScreen(
                onOpenRemote = {
                    relayClaimed = false
                    surface = "remote"
                    autoScanPair = true
                    showPair = true
                },
                onOpenSettings = { showSettings = true },
                onOpenTasks = { utilitySurface = "tasks" },
                onOpenTools = { utilitySurface = "tools" },
                onCheckUpdate = {
                    viewModel.checkForUpdateAndInstall(BuildConfig.VERSION_NAME)
                },
                updateStatus = updateInstallStatus,
            )
            showMain && selectedRemoteMatches -> MainScreen(
                onOpenSettings = { showSettings = true },
                onOpenTasks = { utilitySurface = "tasks" },
                onOpenTools = { utilitySurface = "tools" },
                onOpenLocalHarness = {
                    viewModel.disconnectRemote()
                    relayClaimed = false
                    surface = "local"
                },
            )
            surface == "remote" && relayClaimed -> RemoteRelayStatus(
                failed = connection.failure != null ||
                    (connection.phase == ConnectionPhase.DISCONNECTED && connection.host == null),
                onRetryPairing = {
                    viewModel.disconnectRemote()
                    relayClaimed = false
                    autoScanPair = false
                    showPair = true
                },
                onBack = {
                    viewModel.disconnectRemote()
                    relayClaimed = false
                    surface = "local"
                },
            )
            else -> PairScreen(
                onClose = {
                    relayClaimed = false
                    surface = "local"
                },
                onPaired = {
                    relayClaimed = true
                    surface = "remote"
                },
            )
        }
    }
}

@Composable
private fun RemoteRelayStatus(
    failed: Boolean,
    onRetryPairing: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = DsTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            if (!failed) CircularProgressIndicator(color = colors.accent)
            Text(
                stringResource(
                    if (failed) R.string.relay_status_failed_title else R.string.relay_status_connecting_title,
                ),
                style = DsType.large20,
                color = colors.labelPrimary,
            )
            Text(
                stringResource(
                    if (failed) R.string.relay_status_failed_body else R.string.relay_status_connecting_body,
                ),
                style = DsType.std14,
                color = colors.labelSecondary,
            )
            if (failed) {
                DsButton(
                    stringResource(R.string.relay_status_retry),
                    onRetryPairing,
                    variant = DsButtonVariant.Info,
                )
            }
            DsButton(
                stringResource(R.string.relay_status_back_local),
                onBack,
                variant = DsButtonVariant.Ghost,
            )
        }
    }
}
