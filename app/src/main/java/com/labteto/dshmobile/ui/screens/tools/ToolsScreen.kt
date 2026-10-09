package com.labteto.dshmobile.ui.screens.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.interop.mcp.McpServerSnapshot
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.local.presentation.LocalToolsUiFacade
import com.labteto.dshmobile.local.tools.LocalNetworkSearchSettings
import com.labteto.dshmobile.local.interaction.LocalApprovalMode
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsSwitch
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.DsPageLoadingState
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.ui.screens.settings.SettingsViewModel
import com.labteto.dshmobile.ui.screens.settings.LocalAgentSettingsCard
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val skills: List<com.labteto.dshmobile.local.presentation.LocalSkillUiEntry> = emptyList(),
    val remotePlugins: PluginInventorySnapshot? = null,
    val notice: ToolsNotice? = null,
)

internal class ToolsOperationGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val localTools: LocalToolsUiFacade,
    private val sessionStore: SessionStore,
    private val networkSearchSettings: LocalNetworkSearchSettings,
) : ViewModel() {
    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()
    val networkSearchEnabled: StateFlow<Boolean> = networkSearchSettings.enabled

    fun setNetworkSearchEnabled(enabled: Boolean) = networkSearchSettings.setEnabled(enabled)
    private val operationGate = ToolsOperationGate()

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch {
            operationGate.run(block)
        }
    }

    init {
        refresh()
    }

    fun acknowledgeNotice(notice: ToolsNotice) {
        if (_state.value.notice == notice) {
            _state.value = _state.value.copy(notice = null)
        }
    }

    fun refresh() {
        launchOperation {
            _state.value = _state.value.copy(loading = true, notice = null)
            try {
                sessionStore.refreshPlugins()
                val (servers, plugins) = localTools.servers() to localTools.installedPluginIds()
                _state.value = ToolsUiState(
                    loading = false,
                    servers = servers,
                    githubConfigured = localTools.githubConfigured(),
                    localPlugins = plugins,
                    skills = localTools.installedSkills(),
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
        launchOperation {
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
        launchOperation {
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
        launchOperation {
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
        launchOperation {
            _state.value = _state.value.copy(loading = true, notice = null)
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
                _state.value = _state.value.copy(
                    loading = false,
                    notice = ToolsNotice.DISCONNECT_FAILED,
                )
            }
        }
    }

    fun disconnect(serverId: String) {
        launchOperation {
            _state.value = _state.value.copy(loading = true, notice = null)
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
                _state.value = _state.value.copy(
                    loading = false,
                    notice = ToolsNotice.DISCONNECT_FAILED,
                )
            }
        }
    }


}

@Composable
fun ToolsScreen(
    onClose: () -> Unit,
    startAtPlugins: Boolean = false,
    startAtSkills: Boolean = false,
    onUseCapability: ((String) -> Unit)? = null,
    onOpenSettings: (SettingsDestination) -> Unit = {},
    handleRootSystemBack: Boolean = true,
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val networkSearchEnabled by viewModel.networkSearchEnabled.collectAsStateWithLifecycle()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val approvalMode by settingsViewModel.approvalMode.collectAsStateWithLifecycle()
    val autoApprovalEnabled = approvalMode == LocalApprovalMode.AUTO
    var confirmAutoApproval by remember { mutableStateOf(false) }
    val toggleAutoApproval: (Boolean) -> Unit = { enabled ->
        if (enabled) confirmAutoApproval = true
        else settingsViewModel.configureApprovalMode(LocalApprovalMode.DEFAULT)
    }
    val colors = DsTheme.colors
    val toast = rememberDsToast()
    var serverId by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var stdioCommand by remember { mutableStateOf("") }
    var stdioWorkingDirectory by remember { mutableStateOf("") }
    var githubToken by remember { mutableStateOf("") }
    var showGitHubConfig by remember { mutableStateOf(false) }
    var showExternalConfig by remember { mutableStateOf(false) }
    var expandedMcpServer by remember { mutableStateOf<String?>(null) }
    var showAgentSettings by remember { mutableStateOf(false) }
    var confirmClearGitHub by remember { mutableStateOf(false) }
    var showPluginBrowser by remember(startAtPlugins, startAtSkills) { mutableStateOf(startAtPlugins || startAtSkills) }
    var skillsOnly by remember(startAtSkills) { mutableStateOf(startAtSkills) }

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

    BackHandler(enabled = showPluginBrowser || showAgentSettings || handleRootSystemBack) {
        when {
            showAgentSettings -> showAgentSettings = false
            showPluginBrowser -> showPluginBrowser = false
            else -> onClose()
        }
    }
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

    if (showAgentSettings) {
        val agentSettings by settingsViewModel.localHarnessState.collectAsStateWithLifecycle()
        Surface(Modifier.fillMaxSize(), color = colors.rootSurface()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                DsTopBar(
                    title = stringResource(R.string.advanced_agent_settings),
                    onBack = { showAgentSettings = false },
                    backContentDescription = stringResource(R.string.common_back),
                )
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
                ) {
                    LocalAgentSettingsCard(agentSettings, settingsViewModel, toast.second)
                }
                DsToastHost(toast)
            }
        }
    } else if (showPluginBrowser) {
        PluginInventoryBrowser(
            localIds = state.localPlugins,
            skills = state.skills,
            skillsOnly = skillsOnly,
            loading = state.loading,
            error = if (state.notice == ToolsNotice.LOAD_FAILED) stringResource(R.string.tools_load_failed) else null,
            onRetry = viewModel::refresh,
            remote = state.remotePlugins,
            onBack = { showPluginBrowser = false },
            onManageConnections = {
                showPluginBrowser = false
                showExternalConfig = true
            },
            onReturnToChat = onClose,
            onUseCapability = onUseCapability,
        )
    } else {
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = colors.rootSurface()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                DsTopBar(
                    title = stringResource(R.string.tools_title),
                    subtitle = stringResource(R.string.tools_subtitle),
                    onBack = onClose,
                    backContentDescription = stringResource(R.string.common_back),
                    largeTitle = false,
                    actionIcon = FeatherIcons.RefreshCw,
                    actionContentDescription = stringResource(R.string.tools_refresh),
                    actionEnabled = !state.loading,
                    onAction = viewModel::refresh,
                    modifier = Modifier.padding(
                        horizontal = DsSpacing.comfortable,
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
                        .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.large),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                        Text(
                            stringResource(R.string.tools_capability_group),
                            style = DsType.std14.withReadingWeight(),
                            color = colors.labelTertiary,
                        )
                        Text(
                            stringResource(R.string.tools_capability_group_hint),
                            style = DsType.small13.withReadingWeight(),
                            color = colors.labelSecondary,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        ToolCapabilityRow(
                            icon = FeatherIcons.Globe,
                            title = stringResource(R.string.tools_network_search),
                            subtitle = stringResource(R.string.tools_network_search_hint),
                            status = stringResource(
                                if (networkSearchEnabled) R.string.common_enabled else R.string.common_disabled,
                            ),
                            state = if (networkSearchEnabled) StateDotState.Done else StateDotState.Idle,
                            onClick = { viewModel.setNetworkSearchEnabled(!networkSearchEnabled) },
                            trailing = {
                                DsSwitch(
                                    checked = networkSearchEnabled,
                                    onCheckedChange = viewModel::setNetworkSearchEnabled,
                                )
                            },
                        )
                        ToolCapabilityRow(
                            icon = FeatherIcons.Shield,
                            title = stringResource(R.string.cap_center_auto_approval),
                            subtitle = stringResource(R.string.cap_center_auto_approval_hint),
                            status = stringResource(
                                when (approvalMode) {
                                    LocalApprovalMode.AUTO -> R.string.common_enabled
                                    LocalApprovalMode.MANUAL -> R.string.settings_approval_mode_manual
                                    LocalApprovalMode.DEFAULT -> R.string.common_disabled
                                },
                            ),
                            state = if (autoApprovalEnabled) StateDotState.Done else StateDotState.Idle,
                            onClick = { toggleAutoApproval(!autoApprovalEnabled) },
                            trailing = {
                                DsSwitch(
                                    checked = autoApprovalEnabled,
                                    onCheckedChange = toggleAutoApproval,
                                )
                            },
                        )
                        ToolCapabilityRow(
                            icon = FeatherIcons.Sliders,
                            title = stringResource(R.string.advanced_agent_settings),
                            subtitle = stringResource(R.string.tools_agent_settings_hint),
                            status = stringResource(R.string.tools_capability_available),
                            state = StateDotState.Done,
                            onClick = { showAgentSettings = true },
                        )

                    }

                    Text(
                        stringResource(R.string.cap_center_extensions_group),
                        style = DsType.std14.withReadingWeight(),
                        color = colors.labelTertiary,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        val builtinAvailable = "local-builtin" in state.localPlugins
                        ToolCapabilityRow(
                            iconPainter = painterResource(R.drawable.ic_ui_plugin),
                            title = stringResource(R.string.skills_title),
                            subtitle = stringResource(R.string.tools_capability_agent_available),
                            status = capabilityStateLabel(builtinAvailable),
                            state = if (builtinAvailable) StateDotState.Done else StateDotState.Idle,
                            onClick = { skillsOnly = true; showPluginBrowser = true },
                        )
                        ToolCapabilityRow(
                            icon = FeatherIcons.GitBranch,
                            title = stringResource(R.string.tools_github_connector),
                            subtitle = stringResource(R.string.tools_github_connector_hint),
                            status = stringResource(
                                if (state.githubConfigured) R.string.tools_github_configured
                                else R.string.tools_github_unconfigured,
                            ),
                            state = if (state.githubConfigured) StateDotState.Done else StateDotState.Idle,
                            onClick = { showGitHubConfig = true },
                        )
                        ToolCapabilityRow(
                            iconPainter = painterResource(R.drawable.ic_ui_plugin),
                            title = stringResource(R.string.tools_external_services),
                            subtitle = stringResource(R.string.tools_external_services_hint),
                            status = state.servers.size.toString(),
                            state = if (state.servers.isNotEmpty()) StateDotState.Done else StateDotState.Idle,
                            onClick = { showExternalConfig = true },
                        )
                    }
                }
            }
        }
        DsToastHost(toast, Modifier.safeDrawingPadding())
    }
    }

    if (showGitHubConfig) {
        DsBottomSheet(
            title = stringResource(R.string.tools_github_connector),
            subtitle = stringResource(R.string.tools_github_connector_hint),
            onDismiss = { showGitHubConfig = false },
        ) {
            Text(
                stringResource(R.string.tools_github_capabilities),
                style = DsType.small13.withReadingWeight(),
                color = colors.labelSecondary,
            )
            Text(
                stringResource(R.string.tools_github_write_approval),
                style = DsType.caption11.withReadingWeight(),
                color = colors.labelTertiary,
            )
            DsTextField(
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
                            text = stringResource(R.string.cap_center_view_tools),
                            onClick = { expandedMcpServer = if (expandedMcpServer == server.id) null else server.id },
                            enabled = server.tools.isNotEmpty(),
                            size = DsButtonSize.Small,
                            variant = DsButtonVariant.Ghost,
                        )
                        DsButton(
                            text = stringResource(R.string.tools_disconnect),
                            onClick = { viewModel.disconnect(server.id) },
                            enabled = !state.loading,
                            size = DsButtonSize.Small,
                            variant = DsButtonVariant.Ghost,
                        )
                    }
                    if (expandedMcpServer == server.id) {
                        Text(
                            server.tools.joinToString(" · "),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelSecondary,
                            modifier = Modifier.padding(bottom = DsSpacing.small),
                        )
                    }
                }
            }

            DsTextField(
                value = serverId,
                onValueChange = { serverId = it.take(24) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tools_server_name)) },
                enabled = !state.loading,
                singleLine = true,
                shape = DsShapes.row,
            )
            DsTextField(
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
                icon = FeatherIcons.Globe,
            )
            Text(
                stringResource(R.string.tools_stdio_title),
                style = DsType.std14Strong.withReadingWeight(),
                color = colors.labelPrimary,
            )
            DsTextField(
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
            DsTextField(
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

    if (confirmAutoApproval) {
        DsDialog(
            title = stringResource(R.string.cap_center_auto_approval_confirm_title),
            onDismiss = { confirmAutoApproval = false },
        ) {
            Text(
                stringResource(R.string.cap_center_auto_approval_confirm_body),
                style = DsType.std14.withReadingWeight(),
                color = colors.labelSecondary,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { confirmAutoApproval = false },
                    variant = DsButtonVariant.Ghost,
                )
                DsButton(
                    text = stringResource(R.string.cap_center_auto_approval_confirm_action),
                    onClick = {
                        confirmAutoApproval = false
                        settingsViewModel.configureApprovalMode(LocalApprovalMode.AUTO)
                    },
                    variant = DsButtonVariant.Danger,
                )
            }
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
                    enabled = !state.loading,
                    variant = DsButtonVariant.Ghost,
                )
                DsButton(
                    text = stringResource(R.string.tools_github_clear),
                    onClick = {
                        confirmClearGitHub = false
                        viewModel.clearGitHub()
                    },
                    enabled = !state.loading,
                    variant = DsButtonVariant.Danger,
                )
            }
        }
    }
}

@Composable
private fun ToolCapabilityRow(
    title: String,
    subtitle: String,
    status: String,
    state: StateDotState,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = DsTheme.colors
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        shape = DsShapes.block,
        color = colors.bgLayer1,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 70.dp)
                .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = DsShapes.row,
                color = colors.bgModulePlatform,
                tonalElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        iconPainter != null -> Icon(
                            painter = iconPainter,
                            contentDescription = null,
                            tint = colors.labelSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                        icon != null -> Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = colors.labelSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val stackedStatus = maxWidth < 200.dp || LocalDensity.current.fontScale > 1.15f
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        Text(
                            title,
                            modifier = Modifier.weight(1f),
                            style = DsType.std14Strong.withReadingWeight(),
                            color = colors.labelPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!stackedStatus) ToolCapabilityStatus(status, state)
                    }
                    Text(
                        subtitle,
                        style = DsType.small13.withReadingWeight(),
                        color = colors.labelSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (stackedStatus) ToolCapabilityStatus(status, state)
                }
            }
            if (trailing != null) {
                trailing()
            } else if (onClick != null) {
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.labelCaption,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCapabilityStatus(status: String, state: StateDotState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        StateDot(state, size = 7.dp)
        Text(
            status,
            style = DsType.xsmall12.withReadingWeight(),
            color = DsTheme.colors.labelSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun capabilityStateLabel(available: Boolean): String =
    stringResource(
        if (available) R.string.tools_capability_available
        else R.string.tools_capability_unavailable,
    )
