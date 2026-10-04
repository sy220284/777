package com.labteto.dshmobile.ui.screens.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.PluginFiberPhase
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.interop.mcp.McpServerSnapshot
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.local.tools.LocalToolsRuntime
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCategoryRow
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.DsPageLoadingState
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.rootSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ToolsNotice {
    LOAD_FAILED,
    CONNECTING,
    CONNECTED,
    CONNECT_FAILED,
    DISCONNECTED,
    DISCONNECT_FAILED,
}

data class ToolsUiState(
    val loading: Boolean = false,
    val servers: List<McpServerSnapshot> = emptyList(),
    val githubConfigured: Boolean = false,
    val localPlugins: List<String> = emptyList(),
    val remotePlugins: PluginInventorySnapshot? = null,
    val notice: ToolsNotice? = null,
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val localTools: LocalToolsRuntime,
    private val sessionStore: SessionStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun acknowledgeNotice(notice: ToolsNotice) {
        if (_state.value.notice == notice) {
            _state.value = _state.value.copy(notice = null)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, notice = null)
            try {
                sessionStore.refreshPlugins()
                val (servers, plugins) = localTools.servers() to localTools.installedPluginIds()
                _state.value = ToolsUiState(
                    loading = false,
                    servers = servers,
                    githubConfigured = localTools.githubConfigured(),
                    localPlugins = plugins,
                    remotePlugins = sessionStore.plugins.value,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    notice = ToolsNotice.LOAD_FAILED,
                )
            }
        }
    }

    fun connectHttp(serverId: String, endpoint: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, notice = ToolsNotice.CONNECTING)
            try {
                localTools.connectHttp(serverId, endpoint)
                _state.value = _state.value.copy(
                    loading = false,
                    servers = localTools.servers(),
                    localPlugins = localTools.installedPluginIds(),
                    notice = ToolsNotice.CONNECTED,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    notice = ToolsNotice.CONNECT_FAILED,
                )
            }
        }
    }

    fun connectStdio(serverId: String, command: List<String>, workingDirectory: String?) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, notice = ToolsNotice.CONNECTING)
            try {
                localTools.connectStdio(serverId, command, workingDirectory)
                _state.value = _state.value.copy(
                    loading = false,
                    servers = localTools.servers(),
                    localPlugins = localTools.installedPluginIds(),
                    notice = ToolsNotice.CONNECTED,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    notice = ToolsNotice.CONNECT_FAILED,
                )
            }
        }
    }

    fun configureGitHub(token: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, notice = ToolsNotice.CONNECTING)
            try {
                localTools.configureGitHub(token)
                _state.value = _state.value.copy(
                    loading = false,
                    githubConfigured = true,
                    localPlugins = localTools.installedPluginIds(),
                    notice = ToolsNotice.CONNECTED,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    notice = ToolsNotice.CONNECT_FAILED,
                )
            }
        }
    }

    fun clearGitHub() {
        viewModelScope.launch {
            try {
                localTools.clearGitHub()
                _state.value = _state.value.copy(
                    loading = false,
                    githubConfigured = false,
                    notice = ToolsNotice.DISCONNECTED,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(notice = ToolsNotice.DISCONNECT_FAILED)
            }
        }
    }

    fun disconnect(serverId: String) {
        viewModelScope.launch {
            try {
                localTools.disconnect(serverId)
                _state.value = _state.value.copy(
                    loading = false,
                    servers = localTools.servers(),
                    localPlugins = localTools.installedPluginIds(),
                    notice = ToolsNotice.DISCONNECTED,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(notice = ToolsNotice.DISCONNECT_FAILED)
            }
        }
    }
}

@Composable
fun ToolsScreen(
    onClose: () -> Unit,
    onOpenTasks: () -> Unit = {},
    onOpenSettings: (SettingsDestination) -> Unit = {},
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    val toast = rememberDsToast()
    var serverId by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var stdioCommand by remember { mutableStateOf("") }
    var stdioWorkingDirectory by remember { mutableStateOf("") }
    var githubToken by remember { mutableStateOf("") }
    var showGitHubConfig by remember { mutableStateOf(false) }
    var showExternalConfig by remember { mutableStateOf(false) }
    var confirmClearGitHub by remember { mutableStateOf(false) }

    val noticeMessage = state.notice?.let { notice ->
        stringResource(
            when (notice) {
                ToolsNotice.LOAD_FAILED -> R.string.tools_load_failed
                ToolsNotice.CONNECTING -> R.string.tools_connecting
                ToolsNotice.CONNECTED -> R.string.tools_connected
                ToolsNotice.CONNECT_FAILED -> R.string.tools_connect_failed
                ToolsNotice.DISCONNECTED -> R.string.tools_disconnected
                ToolsNotice.DISCONNECT_FAILED -> R.string.tools_disconnect_failed
            },
        )
    }

    BackHandler(onBack = onClose)
    LaunchedEffect(state.notice, noticeMessage) {
        when (state.notice) {
            ToolsNotice.CONNECTED -> {
                serverId = ""
                endpoint = ""
                stdioCommand = ""
                stdioWorkingDirectory = ""
                githubToken = ""
                showGitHubConfig = false
                showExternalConfig = false
                noticeMessage?.let(toast.second)
            }
            ToolsNotice.LOAD_FAILED,
            ToolsNotice.CONNECT_FAILED,
            ToolsNotice.DISCONNECTED,
            ToolsNotice.DISCONNECT_FAILED -> noticeMessage?.let(toast.second)
            ToolsNotice.CONNECTING, null -> Unit
        }
        state.notice?.takeUnless { it == ToolsNotice.CONNECTING }
            ?.let(viewModel::acknowledgeNotice)
    }

    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = colors.rootSurface()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                DsTopBar(
                    title = stringResource(R.string.tools_title),
                    subtitle = stringResource(R.string.tools_subtitle),
                    onBack = onClose,
                    backContentDescription = stringResource(R.string.common_back),
                largeTitle = true,
                    actionIcon = FeatherIcons.RefreshCw,
                    actionContentDescription = stringResource(R.string.tools_refresh),
                    actionEnabled = !state.loading,
                    onAction = viewModel::refresh,
                    modifier = Modifier.padding(
                        horizontal = DsSpacing.large,
                        vertical = DsSpacing.medium,
                    ),
                )

                val initialLoading = state.loading &&
                    state.localPlugins.isEmpty() &&
                    state.servers.isEmpty() &&
                    state.remotePlugins == null &&
                    !state.githubConfigured
                if (initialLoading) {
                    DsPageLoadingState(
                        icon = FeatherIcons.Tool,
                        label = stringResource(R.string.tools_loading),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                } else Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DsSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.xlarge),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                        Text(
                            stringResource(R.string.tools_capability_group),
                            style = DsType.std14.withReadingWeight(),
                            color = colors.labelTertiary,
                        )
                        Text(
                            stringResource(R.string.tools_capability_group_hint),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelTertiary,
                        )
                    }
                    DsGroupCard {
                        DsCategoryRow(
                            icon = FeatherIcons.Tool,
                            title = stringResource(R.string.skills_title),
                            subtitle = stringResource(R.string.tools_capability_agent_available),
                            value = capabilityStateLabel("local-builtin" in state.localPlugins),
                        )
                        DsCategoryRow(
                            icon = FeatherIcons.Terminal,
                            title = stringResource(R.string.tools_capability_terminal),
                            subtitle = stringResource(R.string.tools_capability_agent_available),
                            value = capabilityStateLabel("android-runtime" in state.localPlugins),
                            onClick = { onOpenSettings(SettingsDestination.ADVANCED) },
                        )
                        DsCategoryRow(
                            icon = FeatherIcons.Code,
                            title = stringResource(R.string.tools_capability_code),
                            subtitle = stringResource(R.string.tools_capability_agent_available),
                            value = capabilityStateLabel(
                                "local-language-server" in state.localPlugins,
                            ),
                            onClick = { onOpenSettings(SettingsDestination.ADVANCED) },
                        )
                        DsCategoryRow(
                            icon = FeatherIcons.Device,
                            title = stringResource(R.string.tools_capability_device),
                            subtitle = stringResource(R.string.tools_capability_agent_available),
                            value = capabilityStateLabel("android-device" in state.localPlugins),
                            onClick = { onOpenSettings(SettingsDestination.PERMISSIONS) },
                        )
                        DsCategoryRow(
                            icon = FeatherIcons.Image,
                            title = stringResource(R.string.tools_capability_vision),
                            subtitle = stringResource(R.string.tools_capability_agent_available),
                            value = capabilityStateLabel("local-vision" in state.localPlugins),
                            onClick = { onOpenSettings(SettingsDestination.MODELS) },
                        )
                        DsCategoryRow(
                            icon = FeatherIcons.Clock,
                            title = stringResource(R.string.tools_capability_automation),
                            subtitle = stringResource(R.string.tools_capability_automation_hint),
                            value = capabilityStateLabel(
                                "android-automation" in state.localPlugins ||
                                    "android-webhook" in state.localPlugins,
                            ),
                            onClick = onOpenTasks,
                        )
                    }

                    Text(
                        stringResource(R.string.tools_connection_group),
                        style = DsType.std14.withReadingWeight(),
                        color = colors.labelTertiary,
                    )
                    DsGroupCard {
                        DsCategoryRow(
                            icon = FeatherIcons.GitBranch,
                            title = stringResource(R.string.tools_github_connector),
                            subtitle = stringResource(R.string.tools_github_connector_hint),
                            value = stringResource(
                                if (state.githubConfigured) R.string.tools_github_configured
                                else R.string.tools_github_unconfigured,
                            ),
                            onClick = { showGitHubConfig = true },
                        )
                        DsCategoryRow(
                            icon = FeatherIcons.Globe,
                            title = stringResource(R.string.tools_external_services),
                            subtitle = stringResource(R.string.tools_external_services_hint),
                            value = state.servers.size.toString(),
                            onClick = { showExternalConfig = true },
                        )
                    }

                    state.remotePlugins?.let { inventory ->
                        Text(
                            stringResource(R.string.tools_remote_extensions),
                            style = DsType.std14.withReadingWeight(),
                            color = colors.labelTertiary,
                        )
                        DsGroupCard {
                            Text(
                                stringResource(
                                    R.string.tools_remote_inventory,
                                    inventory.entries.size,
                                ),
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.labelTertiary,
                            )
                            inventory.entries.forEach { entry ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = DsSpacing.xsmall),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                                ) {
                                    StateDot(
                                        when {
                                            !entry.enabled -> StateDotState.Idle
                                            entry.fiberPhase == PluginFiberPhase.FAILED ->
                                                StateDotState.Error
                                            entry.fiberPhase == PluginFiberPhase.ACTIVE ->
                                                StateDotState.Done
                                            else -> StateDotState.Warning
                                        },
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            shortPluginName(entry.moduleName),
                                            style = DsType.small13.withReadingWeight(),
                                            color = colors.labelPrimary,
                                        )
                                        Text(
                                            pluginPhaseLabel(
                                                entry.fiberPhase,
                                                entry.enabled,
                                            ),
                                            style = DsType.caption11.withReadingWeight(),
                                            color = colors.labelTertiary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        DsToastHost(toast, Modifier.safeDrawingPadding())
    }

    if (showGitHubConfig) {
        DsBottomSheet(
            title = stringResource(R.string.tools_github_connector),
            subtitle = stringResource(R.string.tools_github_connector_hint),
            onDismiss = { showGitHubConfig = false },
        ) {
            OutlinedTextField(
                value = githubToken,
                onValueChange = { githubToken = it.take(4096) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tools_github_token)) },
                supportingText = { Text(stringResource(R.string.tools_github_token_hint)) },
                enabled = !state.loading,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = DsShapes.row,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                DsButton(
                    text = stringResource(
                        if (state.loading) R.string.tools_processing else R.string.tools_github_save,
                    ),
                    onClick = { viewModel.configureGitHub(githubToken) },
                    enabled = !state.loading && githubToken.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    variant = DsButtonVariant.Primary,
                )
                if (state.githubConfigured) {
                    DsButton(
                        text = stringResource(R.string.tools_github_clear),
                        onClick = { confirmClearGitHub = true },
                        enabled = !state.loading,
                        variant = DsButtonVariant.Ghost,
                    )
                }
            }
        }
    }

    if (showExternalConfig) {
        DsBottomSheet(
            title = stringResource(R.string.tools_external_services),
            subtitle = stringResource(R.string.tools_external_services_hint),
            onDismiss = { showExternalConfig = false },
        ) {
            if (state.servers.isEmpty()) {
                Text(
                    stringResource(R.string.tools_no_services),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.labelTertiary,
                )
            } else {
                state.servers.forEach { server ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = DsSpacing.xsmall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        StateDot(StateDotState.Done)
                        Column(Modifier.weight(1f)) {
                            Text(
                                server.id,
                                style = DsType.std14Strong.withReadingWeight(),
                                color = colors.labelPrimary,
                            )
                            Text(
                                stringResource(
                                    R.string.tools_server_summary,
                                    server.transport.uppercase(),
                                    server.tools.size,
                                ),
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.labelTertiary,
                            )
                        }
                        DsButton(
                            text = stringResource(R.string.tools_disconnect),
                            onClick = { viewModel.disconnect(server.id) },
                            size = DsButtonSize.Small,
                            variant = DsButtonVariant.Ghost,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = serverId,
                onValueChange = { serverId = it.take(24) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tools_server_name)) },
                enabled = !state.loading,
                singleLine = true,
                shape = DsShapes.row,
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it.take(2000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tools_endpoint)) },
                supportingText = { Text(stringResource(R.string.tools_endpoint_hint)) },
                enabled = !state.loading,
                singleLine = true,
                shape = DsShapes.row,
            )
            DsButton(
                text = stringResource(
                    if (state.loading) R.string.tools_processing else R.string.tools_connect,
                ),
                onClick = {
                    viewModel.connectHttp(serverId.trim(), endpoint.trim())
                },
                enabled = !state.loading && serverId.isNotBlank() && endpoint.isNotBlank(),
                variant = DsButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Outlined.Link,
            )
            Text(
                stringResource(R.string.tools_stdio_title),
                style = DsType.std14Strong.withReadingWeight(),
                color = colors.labelPrimary,
            )
            OutlinedTextField(
                value = stdioCommand,
                onValueChange = { stdioCommand = it.take(4000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tools_stdio_command)) },
                supportingText = { Text(stringResource(R.string.tools_stdio_command_hint)) },
                enabled = !state.loading,
                minLines = 2,
                maxLines = 6,
                shape = DsShapes.row,
            )
            OutlinedTextField(
                value = stdioWorkingDirectory,
                onValueChange = { stdioWorkingDirectory = it.take(1000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tools_stdio_working_directory)) },
                supportingText = { Text(stringResource(R.string.tools_stdio_working_directory_hint)) },
                enabled = !state.loading,
                singleLine = true,
                shape = DsShapes.row,
            )
            val stdioArgs = stdioCommand.lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toList()
            DsButton(
                text = stringResource(
                    if (state.loading) R.string.tools_processing else R.string.tools_stdio_connect,
                ),
                onClick = {
                    viewModel.connectStdio(
                        serverId.trim(),
                        stdioArgs,
                        stdioWorkingDirectory.trim().takeIf(String::isNotBlank),
                    )
                },
                enabled = !state.loading && serverId.isNotBlank() && stdioArgs.isNotEmpty(),
                variant = DsButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
                icon = FeatherIcons.Terminal,
            )
        }
    }

    if (confirmClearGitHub) {
        DsDialog(
            title = stringResource(R.string.tools_github_clear_confirm_title),
            onDismiss = { confirmClearGitHub = false },
        ) {
            Text(
                stringResource(R.string.tools_github_clear_confirm_body),
                style = DsType.std14.withReadingWeight(),
                color = colors.labelSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { confirmClearGitHub = false },
                    variant = DsButtonVariant.Ghost,
                )
                DsButton(
                    text = stringResource(R.string.tools_github_clear),
                    onClick = {
                        confirmClearGitHub = false
                        viewModel.clearGitHub()
                    },
                    variant = DsButtonVariant.Danger,
                )
            }
        }
    }
}

@Composable
private fun capabilityStateLabel(available: Boolean): String =
    stringResource(
        if (available) R.string.tools_capability_available
        else R.string.tools_capability_unavailable,
    )

@Composable
private fun pluginPhaseLabel(phase: PluginFiberPhase?, enabled: Boolean): String = when {
    !enabled -> stringResource(R.string.tools_plugin_disabled)
    phase == PluginFiberPhase.ACTIVE -> stringResource(R.string.tools_plugin_active)
    phase == PluginFiberPhase.FAILED -> stringResource(R.string.tools_plugin_failed)
    phase != null -> phase.name.lowercase()
    else -> stringResource(R.string.tools_plugin_enabled)
}

private fun shortPluginName(moduleName: String): String =
    moduleName.substringAfterLast('/').removePrefix("dsh-host-").removePrefix("dsh-client-")
