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
import androidx.compose.material.icons.outlined.Link
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.core.wire.dto.PluginFiberPhase
import com.labteto.dshmobile.interop.mcp.McpServerSnapshot
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ToolsUiState(
    val loading: Boolean = false,
    val servers: List<McpServerSnapshot> = emptyList(),
    val localPlugins: List<String> = emptyList(),
    val message: String? = null,
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
            _state.value = _state.value.copy(loading = true, message = null)
            runCatching {
                engine.mcpServersForUi() to engine.installedPluginIdsForUi()
            }.onSuccess { (servers, plugins) ->
                _state.value = ToolsUiState(
                    loading = false,
                    servers = servers,
                    localPlugins = plugins,
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    loading = false,
                    message = error.message ?: "读取工具状态失败",
                )
            }
        }
    }

    fun connectHttp(serverId: String, endpoint: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = "正在连接外部工具服务…")
            runCatching { engine.connectMcpHttpForUi(serverId, endpoint) }
                .onSuccess { message ->
                    _state.value = ToolsUiState(
                        loading = false,
                        servers = engine.mcpServersForUi(),
                        localPlugins = engine.installedPluginIdsForUi(),
                        message = message,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        message = error.message ?: "连接失败",
                    )
                }
        }
    }

    fun disconnect(serverId: String) {
        viewModelScope.launch {
            runCatching { engine.disconnectMcpForUi(serverId) }
                .onSuccess { message ->
                    _state.value = ToolsUiState(
                        servers = engine.mcpServersForUi(),
                        localPlugins = engine.installedPluginIdsForUi(),
                        message = message,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(message = error.message ?: "断开失败")
                }
        }
    }
}

@Composable
fun ToolsScreen(
    onClose: () -> Unit,
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val store = rememberSessionStore()
    val remotePlugins by store.plugins.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    var serverId by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        runCatching { store.refreshPlugins() }
    }

    Surface(Modifier.fillMaxSize(), color = colors.bgBase) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DsSpacing.large, vertical = DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xlarge),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DsIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onClose,
                    containerColor = colors.bgLayer1,
                    shadowElevation = 3.dp,
                )
                Column(Modifier.weight(1f).padding(horizontal = DsSpacing.medium)) {
                    Text("工具与连接", style = DsType.large20, color = colors.labelPrimary)
                    Text("管理外部工具服务和当前可用扩展", style = DsType.caption11, color = colors.labelTertiary)
                }
                DsIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    onClick = viewModel::refresh,
                    containerColor = colors.bgLayer1,
                )
            }

            Text("外部工具服务", style = DsType.std14, color = colors.labelTertiary)
            DsGroupCard {
                if (state.servers.isEmpty()) {
                    Text("暂无已连接服务", style = DsType.small13, color = colors.labelTertiary)
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
                                    "${server.transport.uppercase()} · ${server.target} · ${server.tools.size} 个工具",
                                    style = DsType.caption11,
                                    color = colors.labelTertiary,
                                )
                            }
                            DsButton(
                                text = "断开",
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
                    label = { Text("服务名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it.take(2000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTP / HTTPS 地址") },
                    supportingText = { Text("连接后会自动发现工具；外部工具仍按高风险能力处理。") },
                    singleLine = true,
                )
                DsButton(
                    text = if (state.loading) "处理中…" else "连接服务",
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

            Text("本机能力", style = DsType.std14, color = colors.labelTertiary)
            DsGroupCard {
                Text(
                    "已加载 ${state.localPlugins.size} 个本机能力模块",
                    style = DsType.small13,
                    color = colors.labelSecondary,
                )
                state.localPlugins.forEach { id ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = DsSpacing.xsmall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        StateDot(StateDotState.Done)
                        Text(localPluginLabel(id), style = DsType.small13, color = colors.labelPrimary)
                    }
                }
            }

            remotePlugins?.let { inventory ->
                Text("远程 Harness 扩展", style = DsType.std14, color = colors.labelTertiary)
                DsGroupCard {
                    Text(
                        "${inventory.entries.size} 个扩展，仅显示当前加载状态",
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
                                    entry.fiberPhase?.name?.lowercase() ?: if (entry.enabled) "已启用" else "未启用",
                                    style = DsType.caption11,
                                    color = colors.labelTertiary,
                                )
                            }
                        }
                    }
                }
            }

            state.message?.let {
                Text(it, style = DsType.small13, color = colors.labelSecondary)
            }
        }
    }
}

private fun localPluginLabel(id: String): String = when (id) {
    "local-builtin" -> "基础文件与网页工具"
    "android-runtime" -> "本机进程与终端"
    "mcp-bridge" -> "外部工具服务"
    "local-language-server" -> "代码语言服务"
    "android-device" -> "Android 设备控制"
    "local-vision" -> "视觉分析"
    "android-automation" -> "后台任务"
    "android-webhook" -> "外部回调"
    else -> id
}

private fun shortPluginName(moduleName: String): String =
    moduleName.substringAfterLast('/').removePrefix("dsh-host-").removePrefix("dsh-client-")
