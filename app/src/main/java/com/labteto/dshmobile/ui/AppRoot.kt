package com.labteto.dshmobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIconBox
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.screens.local.LocalHarnessScreen
import com.labteto.dshmobile.ui.screens.main.MainScreen
import com.labteto.dshmobile.ui.screens.pair.PairScreen
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.ui.screens.settings.SettingsScreen
import com.labteto.dshmobile.ui.screens.tasks.TasksScreen
import com.labteto.dshmobile.ui.screens.tools.ToolsScreen
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.labteto.dshmobile.ui.theme.rootSurface

private enum class RootSurface { LOCAL, REMOTE }
private enum class RootOverlay { TASKS, TOOLS, SETTINGS, PAIR }

/** Application root: theme-aware shell, connect vs. main routing. */
@Composable
fun AppRoot(
    viewModel: AppViewModel = hiltViewModel(),
    requestedSessionId: String? = null,
    requestedLocalSessionId: String? = null,
    onSessionRequestConsumed: () -> Unit = {},
    onLocalSessionRequestConsumed: () -> Unit = {},
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
        accentKey = settings.accentTheme,
        backgroundPath = settings.backgroundImagePath,
        backgroundAdaptiveContrast = settings.backgroundAdaptiveContrast,
        textScale = settings.textScale,
        textWeightAdjustment = settings.textWeightAdjustment,
        wallpaperSurfaceTransparency = settings.wallpaperSurfaceTransparency,
    ) {
        var rootSurface by rememberSaveable { mutableStateOf(RootSurface.LOCAL) }
        var overlay by rememberSaveable { mutableStateOf<RootOverlay?>(null) }
        var overlayReturn by rememberSaveable { mutableStateOf<RootOverlay?>(null) }
        var settingsDestination by rememberSaveable { mutableStateOf(SettingsDestination.ROOT) }
        var taskMode by rememberSaveable { mutableStateOf<AutomationMode?>(null) }
        var localNavigationSessionId by rememberSaveable { mutableStateOf<String?>(null) }
        val effectiveLocalSessionId = requestedLocalSessionId ?: localNavigationSessionId

        // Local Harness remains the home surface. Remote control is a peer root surface, while
        // settings/tasks/tools/pairing are explicit overlays with one return destination.
        var autoScanPair by rememberSaveable { mutableStateOf(false) }
        var relayClaimed by rememberSaveable { mutableStateOf(false) }
        val showMain = connection.phase == ConnectionPhase.CONNECTED ||
            (connection.phase == ConnectionPhase.RECONNECTING && connection.hasConnected)
        val selectedRemoteMatches = rootSurface == RootSurface.REMOTE && connection.host != null

        LaunchedEffect(requestedSessionId) {
            if (!requestedSessionId.isNullOrBlank()) viewModel.prepareNotificationNavigation()
        }
        LaunchedEffect(effectiveLocalSessionId) {
            if (!effectiveLocalSessionId.isNullOrBlank()) {
                overlay = null
                overlayReturn = null
                relayClaimed = false
                rootSurface = RootSurface.LOCAL
            }
        }
        LaunchedEffect(requestedSessionId, connection.phase) {
            val target = requestedSessionId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
            overlay = null
            overlayReturn = null
            rootSurface = RootSurface.REMOTE
            relayClaimed = true
            if (connection.phase == ConnectionPhase.CONNECTED && viewModel.openNotificationSession(target)) {
                onSessionRequestConsumed()
            }
        }

        when (overlay) {
            RootOverlay.TASKS -> TasksScreen(
                onClose = {
                    overlay = overlayReturn
                    overlayReturn = null
                    taskMode = null
                },
                onOpenSession = { sessionId ->
                    overlay = null
                    overlayReturn = null
                    viewModel.disconnectRemote()
                    relayClaimed = false
                    rootSurface = RootSurface.LOCAL
                    localNavigationSessionId = sessionId
                    taskMode = null
                },
                initialMode = taskMode,
            )

            RootOverlay.TOOLS -> ToolsScreen(
                onClose = { overlay = null },
                onOpenSettings = { destination ->
                    overlayReturn = RootOverlay.TOOLS
                    settingsDestination = destination
                    overlay = RootOverlay.SETTINGS
                },
            )

            RootOverlay.SETTINGS -> SettingsScreen(
                onRemoteControl = {
                    overlay = null
                    overlayReturn = null
                    if (rootSurface == RootSurface.REMOTE) {
                        viewModel.disconnectRemote()
                        relayClaimed = false
                        rootSurface = RootSurface.LOCAL
                    } else {
                        relayClaimed = false
                        rootSurface = RootSurface.REMOTE
                        autoScanPair = true
                        overlay = RootOverlay.PAIR
                    }
                },
                remoteControlActive = rootSurface == RootSurface.REMOTE,
                onClose = {
                    overlay = overlayReturn
                    overlayReturn = null
                },
                initialDestination = settingsDestination,
                onCheckUpdate = {
                    viewModel.checkForUpdateAndInstall(BuildConfig.VERSION_NAME)
                },
                updateStatus = updateInstallStatus,
            )

            RootOverlay.PAIR -> PairScreen(
                autoScanOnOpen = autoScanPair,
                onClose = {
                    overlay = null
                    autoScanPair = false
                    relayClaimed = false
                    rootSurface = RootSurface.LOCAL
                },
                onPaired = {
                    overlay = null
                    autoScanPair = false
                    relayClaimed = true
                    rootSurface = RootSurface.REMOTE
                },
            )

            null -> when {
                rootSurface == RootSurface.LOCAL -> LocalHarnessScreen(
                    requestedSessionId = effectiveLocalSessionId,
                    onSessionRequestConsumed = {
                        if (!requestedLocalSessionId.isNullOrBlank()) {
                            onLocalSessionRequestConsumed()
                        }
                        localNavigationSessionId = null
                    },
                    onOpenRemote = {
                        relayClaimed = false
                        rootSurface = RootSurface.REMOTE
                        autoScanPair = true
                        overlay = RootOverlay.PAIR
                    },
                    onCheckUpdate = {
                        viewModel.checkForUpdateAndInstall(BuildConfig.VERSION_NAME)
                    },
                    updateStatus = updateInstallStatus,
                )

                showMain && selectedRemoteMatches -> MainScreen(
                    onOpenSettings = {
                        overlayReturn = null
                        settingsDestination = SettingsDestination.ROOT
                        overlay = RootOverlay.SETTINGS
                    },
                    onOpenTasks = {
                        overlayReturn = null
                        taskMode = AutomationMode.WORK
                        overlay = RootOverlay.TASKS
                    },
                    onOpenTools = {
                        overlayReturn = null
                        overlay = RootOverlay.TOOLS
                    },
                    onOpenLocalHarness = {
                        viewModel.disconnectRemote()
                        relayClaimed = false
                        rootSurface = RootSurface.LOCAL
                    },
                )

                rootSurface == RootSurface.REMOTE && relayClaimed -> RemoteRelayStatus(
                    failed = connection.failure != null ||
                        (connection.phase == ConnectionPhase.DISCONNECTED && connection.host == null),
                    onRetryPairing = {
                        viewModel.disconnectRemote()
                        relayClaimed = false
                        autoScanPair = false
                        overlay = RootOverlay.PAIR
                    },
                    onBack = {
                        viewModel.disconnectRemote()
                        relayClaimed = false
                        rootSurface = RootSurface.LOCAL
                    },
                )

                else -> PairScreen(
                    onClose = {
                        relayClaimed = false
                        rootSurface = RootSurface.LOCAL
                    },
                    onPaired = {
                        relayClaimed = true
                        rootSurface = RootSurface.REMOTE
                    },
                )
            }
        }
    }
}

@Composable
internal fun RemoteRelayStatus(
    failed: Boolean,
    onRetryPairing: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val colors = DsTheme.colors
    Box(
        Modifier.fillMaxSize().background(colors.rootSurface()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            DsIconBox(
                icon = if (failed) FeatherIcons.AlertTriangle else FeatherIcons.Device,
                active = !failed,
            )
            Text(
                stringResource(
                    if (failed) R.string.relay_status_failed_title else R.string.relay_status_connecting_title,
                ),
                style = DsType.large20.withReadingWeight(),
                color = colors.labelPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = DsSpacing.xlarge),
            )
            Text(
                stringResource(
                    if (failed) R.string.relay_status_failed_body else R.string.relay_status_connecting_body,
                ),
                style = DsType.std14.withReadingWeight(),
                color = colors.labelSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = DsSpacing.xlarge),
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
