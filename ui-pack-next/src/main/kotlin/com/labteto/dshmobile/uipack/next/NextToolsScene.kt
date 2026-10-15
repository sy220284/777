package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.ToolsUiPort
import com.labteto.dshmobile.uicontract.ToolsUiCommand
import com.labteto.dshmobile.uicontract.ToolsUiReceipt
import com.labteto.dshmobile.uicontract.UiMcpServerSnapshot
import com.labteto.dshmobile.uicontract.UiSkillSnapshot
import com.labteto.dshmobile.uicontract.UiToolConnection
import com.labteto.dshmobile.uicontract.UiToolRegistration
import com.labteto.dshmobile.uicontract.UiToolError
import com.labteto.dshmobile.uicontract.submit
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class NextToolsPage { OVERVIEW, MCP, PLUGINS, GITHUB }

data class NextToolsSnapshot(
    val servers: List<UiMcpServerSnapshot>,
    val plugins: List<String>,
    val skills: List<UiSkillSnapshot>,
    val githubConfigured: Boolean,
)

/** A read does not grant permissions or claim remote health. */
suspend fun readNextToolsSnapshot(owner: ToolsUiPort) = NextToolsSnapshot(
    servers = owner.servers(),
    plugins = owner.installedPluginIds().filterNot { it == "local-builtin" },
    skills = owner.installedSkills(),
    githubConfigured = owner.githubConfigured(),
)

@Composable
private fun mcpLifecycleLabel(connection: UiToolConnection) = stringResource(when (connection) {
    UiToolConnection.HEALTH_UNCHECKED -> R.string.next_tools_health_unchecked
    UiToolConnection.CONNECTING -> R.string.next_tools_connecting
    UiToolConnection.RECONNECTING -> R.string.next_tools_reconnecting
    UiToolConnection.DISCONNECTING -> R.string.next_tools_disconnecting
})

/** An independently compiled Tools UI. Everything visible comes from ToolsUiPort. */
@Composable
fun NextToolsScene(
    owner: ToolsUiPort,
    page: NextToolsPage,
    onBack: () -> Unit,
    onOpenMcp: () -> UiSceneNavigationResult,
    onOpenPlugins: () -> UiSceneNavigationResult,
    onOpenGithub: () -> UiSceneNavigationResult = { UiSceneNavigationResult.AtRoot },
) {
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember(owner) { mutableStateOf<NextToolsSnapshot?>(null) }
    var readError by remember(owner) { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var pendingDisconnect by remember { mutableStateOf<UiMcpServerSnapshot?>(null) }
    var serverId by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf("") }
    // Secret is only transient editor state, never part of SavedState.
    var githubToken by remember { mutableStateOf("") }
    var confirmClearGithub by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val applied = stringResource(R.string.next_tools_connection_applied)
    val disconnected = stringResource(R.string.next_tools_disconnect_applied)
    val errorLabel = stringResource(R.string.next_tools_read_failed)
    val ownerErrorPrefix = stringResource(R.string.next_tools_owner_failed, "")
    fun request(command: ToolsUiCommand, success: String) {
        coroutineScope.launch {
            busy = true
            try {
                val receipt = owner.submit(command)
                feedback = when (receipt) {
                    is ToolsUiReceipt.Applied -> success
                    is ToolsUiReceipt.Failed -> ownerErrorPrefix + receipt.reason.name
                }
                revision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally { busy = false }
        }
    }
    LaunchedEffect(owner, revision) {
        readError = null
        try { snapshot = readNextToolsSnapshot(owner) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { snapshot = null; readError = error.message ?: errorLabel }
    }
    if (confirmClearGithub) {
        AlertDialog(onDismissRequest = { confirmClearGithub = false },
            title = { Text("移除 GitHub 连接凭据？") },
            text = { Text("不删除已有文件，但 GitHub 工具将无法继续访问。") },
            confirmButton = { TextButton(onClick = {
                confirmClearGithub = false
                request(ToolsUiCommand.ClearGitHub, "已提交移除凭据")
            }) { Text("移除凭据") } },
            dismissButton = { TextButton(onClick = { confirmClearGithub = false }) { Text("取消") } })
    }
    if (pendingDisconnect != null) {
        val target = pendingDisconnect!!
        AlertDialog(
            onDismissRequest = { pendingDisconnect = null },
            title = { Text(stringResource(R.string.next_tools_disconnect)) },
            text = { Text(stringResource(R.string.next_tools_disconnect_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDisconnect = null
                    request(ToolsUiCommand.Disconnect(target.id), disconnected)
                }) { Text(stringResource(R.string.next_tools_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDisconnect = null }) {
                    Text(stringResource(R.string.next_cancel))
                }
            },
        )
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.next_tools_title), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text(stringResource(R.string.next_close)) }
        }
        if (page == NextToolsPage.OVERVIEW) {
            TextButton(onClick = {
                val result = onOpenMcp()
                if (result is UiSceneNavigationResult.Rejected) feedback = errorLabel
            }) { Text(stringResource(R.string.next_tools_mcp)) }
            TextButton(onClick = {
                val result = onOpenPlugins()
                if (result is UiSceneNavigationResult.Rejected) feedback = errorLabel
            }) { Text(stringResource(R.string.next_tools_plugins)) }
        }
        if (page == NextToolsPage.OVERVIEW) {
            TextButton(onClick = { onOpenGithub() }) { Text("GitHub 连接") }
        }
        TextButton(enabled = !busy, onClick = { revision++ }) {
            Text(stringResource(R.string.next_tools_refresh))
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        readError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val facts = snapshot
        if (facts == null && readError == null) Text(stringResource(R.string.next_tools_loading))
        if (facts != null) {
            if (page == NextToolsPage.OVERVIEW) {
                Text(stringResource(if (facts.githubConfigured)
                    R.string.next_tools_github_configured else R.string.next_tools_github_missing))
                Text(stringResource(R.string.next_tools_summary,
                    facts.servers.count { it.registration == UiToolRegistration.REGISTERED },
                    facts.skills.size))
            }
            if (page != NextToolsPage.PLUGINS) {
                Text(stringResource(R.string.next_tools_mcp), style = MaterialTheme.typography.titleMedium)
                if (facts.servers.isEmpty()) Text(stringResource(R.string.next_tools_mcp_none))
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(facts.servers, key = { it.id }) { server ->
                        Column {
                            Text(server.id, style = MaterialTheme.typography.titleSmall)
                            Text(mcpLifecycleLabel(server.connection))
                            Text(stringResource(R.string.next_tools_server_info, server.transport, server.tools.size))
                            if (page == NextToolsPage.MCP && server.registration == UiToolRegistration.REGISTERED) {
                                TextButton(onClick = { pendingDisconnect = server }, enabled = !busy) {
                                    Text(stringResource(R.string.next_tools_disconnect))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                if (page == NextToolsPage.MCP) {
                    OutlinedTextField(serverId, { serverId = it }, label = {
                        Text(stringResource(R.string.next_tools_server_id))
                    }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(endpoint, { endpoint = it }, label = {
                        Text(stringResource(R.string.next_tools_server_endpoint))
                    }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        request(ToolsUiCommand.ConnectHttp(serverId, endpoint), applied)
                    }, enabled = !busy && serverId.isNotBlank() && endpoint.isNotBlank()) {
                        Text(stringResource(R.string.next_tools_connect))
                    }
                }
            }
            if (page == NextToolsPage.GITHUB) {
                Text(if (facts.githubConfigured) "已保存 GitHub 凭据（未验证当前权限）" else "尚未配置 GitHub")
                OutlinedTextField(
                    githubToken, { githubToken = it },
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("GitHub 访问令牌") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(onClick = {
                    val token = githubToken
                    githubToken = ""
                    request(ToolsUiCommand.ConfigureGitHub(token), "GitHub 凭据已提交；实际权限需调用时校验")
                }, enabled = !busy && githubToken.isNotBlank()) { Text("保存凭据") }
                if (facts.githubConfigured) {
                    TextButton(onClick = { confirmClearGithub = true }, enabled = !busy) { Text("移除 GitHub 凭据") }
                }
            }
            if (page == NextToolsPage.PLUGINS) {
                Text(stringResource(R.string.next_tools_plugins), style = MaterialTheme.typography.titleMedium)
                if (facts.plugins.isEmpty() && facts.skills.isEmpty()) {
                    Text(stringResource(R.string.next_tools_plugins_none))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(facts.plugins.distinct()) { plugin ->
                        Text(stringResource(R.string.next_tools_plugin, plugin))
                    }
                    items(facts.skills, key = { it.name }) { skill ->
                        Text(stringResource(R.string.next_tools_skill, skill.displayName))
                    }
                }
            }
        }
    }
}
