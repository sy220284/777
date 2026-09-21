package com.labteto.dshmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.screens.local.LocalHarnessScreen
import com.labteto.dshmobile.ui.screens.main.MainScreen
import com.labteto.dshmobile.ui.screens.pair.PairScreen
import com.labteto.dshmobile.ui.screens.settings.SettingsScreen
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.labteto.dshmobile.update.AvailableUpdate

/** Application root: theme + locale-aware shell, connect vs. main routing. */
@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val themePreference = remember(settings.themePreference) {
        runCatching { ThemePreference.valueOf(settings.themePreference.uppercase()) }
            .getOrDefault(ThemePreference.SYSTEM)
    }

    val update by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val updateInstallStatus by viewModel.updateInstallStatus.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.checkForUpdate(BuildConfig.VERSION_NAME) }

    DshTheme(preference = themePreference) {
        var showSettings by rememberSaveable { mutableStateOf(false) }
        // Local Harness is always the product home. Remote control has exactly one transport:
        // a paired relay. Opening it lands on relay pairing first; a successful pair connects and
        // carries the user into the remote session, while Back returns to the local home.
        var showPair by rememberSaveable { mutableStateOf(false) }
        var relayClaimed by rememberSaveable { mutableStateOf(false) }
        var surface by rememberSaveable { mutableStateOf("local") }
        val showMain = connection.phase == ConnectionPhase.CONNECTED ||
            (connection.phase == ConnectionPhase.RECONNECTING && connection.hasConnected)
        val selectedRemoteMatches = surface == "remote" && connection.host != null
        when {
            showSettings -> SettingsScreen(onClose = { showSettings = false })
            showPair -> PairScreen(
                onClose = {
                    showPair = false
                    relayClaimed = false
                    surface = "local"
                },
                onPaired = {
                    showPair = false
                    relayClaimed = true
                    surface = "remote"
                },
            )
            surface == "local" -> LocalHarnessScreen(
                onOpenRemote = {
                    relayClaimed = false
                    surface = "remote"
                    showPair = true
                },
                onOpenSettings = { showSettings = true },
            )
            showMain && selectedRemoteMatches -> MainScreen(
                onOpenSettings = { showSettings = true },
                onOpenLocalHarness = {
                    relayClaimed = false
                    surface = "local"
                },
            )
            surface == "remote" && relayClaimed -> RemoteRelayStatus(
                failed = connection.failure != null,
                onRetryPairing = {
                    viewModel.disconnectRemote()
                    relayClaimed = false
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

        // Offered over whatever is on screen, and only once per release: dismissing records the
        // version, so the next launch is quiet until there is a newer one.
        update?.let {
            UpdateDialog(
                update = it,
                status = updateInstallStatus,
                onInstall = { viewModel.installUpdate(it) },
                onDismiss = {
                    viewModel.clearUpdateInstallStatus()
                    viewModel.dismissUpdate(it.version)
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
                if (failed) "中继连接失败" else "正在连接中继…",
                style = DsType.large20,
                color = colors.labelPrimary,
            )
            Text(
                if (failed) "请重新配对中继，或返回本机 Harness。" else "配对已完成，正在建立远程控制连接。",
                style = DsType.std14,
                color = colors.labelSecondary,
            )
            if (failed) {
                DsButton("重新配对", onRetryPairing, variant = DsButtonVariant.Info)
            }
            DsButton("返回本机", onBack, variant = DsButtonVariant.Ghost)
        }
    }
}

/** Verified in-app update handoff; Android's system package installer still owns final consent. */
@Composable
private fun UpdateDialog(
    update: AvailableUpdate,
    status: String?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val uriHandler = LocalUriHandler.current
    DsDialog(title = stringResource(R.string.update_available_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.update_available_body, update.version, BuildConfig.VERSION_NAME),
            style = DsType.std14,
            color = colors.labelSecondary,
            modifier = Modifier.padding(bottom = DsSpacing.medium),
        )
        status?.let { message ->
            Text(
                text = message,
                style = DsType.std14,
                color = colors.labelSecondary,
                modifier = Modifier.padding(bottom = DsSpacing.small),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton(
                text = stringResource(R.string.update_open),
                onClick = {
                    if (update.apkUrl != null && update.checksumUrl != null) {
                        onInstall()
                    } else {
                        runCatching { uriHandler.openUri(update.url) }
                    }
                },
                variant = DsButtonVariant.Info,
            )
            DsButton(
                text = stringResource(R.string.update_later),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
            )
        }
    }
}
