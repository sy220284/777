package com.labteto.dshmobile.ui.screens.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.PluginFiberPhase
import com.labteto.dshmobile.interop.mcp.McpServerSnapshot
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCategoryRow
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.wallpaperSurface
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
    val localPlugins: List<String> = emptyList(),
    val notice: ToolsNotice? = null,
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val engine: LocalHarnessEngine,
) : ViewModel() {
    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, notice = null)
            try {
                val (servers, plugins) = engine.mcpServersForUi() to engine.installedPluginIdsForUi()
                _state.value = ToolsUiState(
                    loading = false,
                    servers = servers,
                    localPlugins = plugins,
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
                engine.connectMcpHttpForUi(serverId, endpoint)
                _state.value = ToolsUiState(
                    loading = false,
                    servers = engine.mcpServersForUi(),
                    localPlugins = engine.installedPluginIdsForUi(),
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

    fun disconnect(serverId: String) {
        viewModelScope.launch {
            try {
                engine.disconnectMcpForUi(serverId)
                _state.value = ToolsUiState(
                    servers = engine.mcpServersForUi(),
                    localPlugins = engine.installedPluginIdsForUi(),
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
    val store = rememberSessionStore()
    val remotePlugins by store.plugins.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    var serverId by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var showExternalConfig by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        runCatching { store.refreshPlugins() }
    }

    Surface(Modifier.fillMaxSize(), color = colors.rootSurface()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DsSpacing.large, vertical = DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xlarge),
        ) {
            DsTopBar(
                title = stringResource(R.string.tools_title),
                subtitle = stringResource(R.string.tools_subtitle),
                onBack = onClose,
                backContentDescription = stringResource(R.string.common_back),
                actionIcon = Icons.Outlined.Refresh,
                actionContentDescription = stringResource(R.string.tools_refresh),
                onAction = viewModel::refresh,
            )

            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Text(stringResource(R.string.tools_capability_group), style = DsType.std14, color = colors.labelTertiary)
                Text(stringResource(R.string.tools_capability_group_hint), style = DsType.caption11, color = colors.labelTertiary)
            }
            DsGroupCard {
                DsCategoryRow(
                    icon = Icons.Outlined.Extension,
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
                    value = capabilityStateLabel("local-language-server" in state.localPlugins),
                    onClick = { onOpenSettings(SettingsDestination.ADVANCED) },
                )
                DsCategoryRow(
                    icon = Icons.Outlined.PhoneAndroid,
                    title = stringResource(R.string.tools_capability_device),
                    subtitle = stringResource(R.string.tools_capability_agent_available),
                    value = capabilityStateLabel("android-device" in state.localPlugins),
                    onClick = { onOpenSettings(SettingsDestination.PERMISSIONS) },
                )
                DsCategoryRow(
                    icon = Icons.Outlined.Image,
                    title = stringResource(R.string.tools_capability_vision),
                    subtitle = stringResource(R.string.tools_capability_agent_available),
                    value = capabilityStateLabel("local-vision" in state.localPlugins),
                    onClick = { onOpenSettings(SettingsDestination.MODELS) },
                )
                DsCategoryRow(
                    icon = Icons.Outlined.Schedule,
                    title = stringResource(R.string.tools_capability_automation),
                    subtitle = stringResource(R.string.tools_capability_automation_hint),
                    value = capabilityStateLabel(
                        "android-automation" in state.localPlugins || "android-webhook" in state.localPlugins,
                    ),
                    onClick = onOpenTasks,
                )
            }

            Text(stringResource(R.string.tools_connection_group), style = DsType.std14, color = colors.labelTertiary)
            DsGroupCard {
                DsCategoryRow(
                    icon = Icons.Outlined.Link,
                    title = stringResource(R.string.tools_external_services),
                    subtitle = stringResource(R.string.tools_external_services_hint),
                    value = state.servers.size.toString(),
                    onClick = { showExternalConfig = !showExternalConfig },
                )

                if (showExternalConfig) {
                    if (state.servers.isEmpty()) {
                        Text(stringResource(R.string.tools_no_services), style = DsType.small13, color = colors.labelTertiary)
                    } else {
                        state.servers.forEach { server ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = DsSpacing.xsmall),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                            ) {
                                StateDot(StateDotState.Done)
                                Column(Modifier.weight(1f)) {
                                    Text(server.id, style = DsType.std14Strong, color = colors.labelPrimary)
                                    Text(
                                        stringResource(R.string.tools_server_summary, server.transport.uppercase(), server.tools.size),
                                        style = DsType.caption11,
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
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it.take(2000) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.tools_endpoint)) },
                        supportingText = { Text(stringResource(R.string.tools_endpoint_hint)) },
                        singleLine = true,
                    )
                    DsButton(
                        text = stringResource(if (state.loading) R.string.tools_processing else R.string.tools_connect),
                        onClick = {
                            viewModel.connectHttp(serverId.trim(), endpoint.trim())
                            serverId = ""
                            endpoint = ""
                        },
                        enabled = !state.loading && serverId.isNotBlank() && endpoint.isNotBlank(),
                        variant = DsButtonVariant.Outline,
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Outlined.Link,
                    )
                }
            }

            remotePlugins?.let { inventory ->
                Text(stringResource(R.string.tools_remote_extensions), style = DsType.std14, color = colors.labelTertiary)
                DsGroupCard {
                    Text(
                        stringResource(R.string.tools_remote_inventory, inventory.entries.size),
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                    )
                    inventory.entries.forEach { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = DsSpacing.xsmall),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                        ) {
                            StateDot(
                                when {
                                    !entry.enabled -> StateDotState.Idle
                                    entry.fiberPhase == PluginFiberPhase.FAILED -> StateDotState.Error
                                    entry.fiberPhase == PluginFiberPhase.ACTIVE -> StateDotState.Done
                                    else -> StateDotState.Warning
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(shortPluginName(entry.moduleName), style = DsType.small13, color = colors.labelPrimary)
                                Text(
                                    pluginPhaseLabel(entry.fiberPhase, entry.enabled),
                                    style = DsType.caption11,
                                    color = colors.labelTertiary,
                                )
                            }
                        }
                    }
                }
            }

            state.notice?.let { notice ->
                val message = when (notice) {
                    ToolsNotice.LOAD_FAILED -> R.string.tools_load_failed
                    ToolsNotice.CONNECTING -> R.string.tools_connecting
                    ToolsNotice.CONNECTED -> R.string.tools_connected
                    ToolsNotice.CONNECT_FAILED -> R.string.tools_connect_failed
                    ToolsNotice.DISCONNECTED -> R.string.tools_disconnected
                    ToolsNotice.DISCONNECT_FAILED -> R.string.tools_disconnect_failed
                }
                Text(stringResource(message), style = DsType.small13, color = colors.labelSecondary)
            }
        }
    }
}

@Composable
private fun capabilityStateLabel(available: Boolean): String =
    stringResource(if (available) R.string.tools_capability_available else R.string.tools_capability_unavailable)

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
