package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.provider.OpenableColumns
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.local.model.*
import com.labteto.dshmobile.local.tools.*
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.shenyan.designsystem.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Each optional secondary Owner is the existing App singleton, never a copied runtime. */
data class V6SecondaryPorts(
    val tools: ToolsUiPort? = null,
    val files: FilesUiPort? = null,
    val media: MediaUiPort? = null,
    val settings: SettingsUiPort? = null,
    val sessionId: () -> String? = { null },
    val automation: AutomationUiPort? = null,
    val sessions: SessionUiPort? = null,
    val transcript: UiMessageHistoryPort? = null,
    val memory: MemoryUiPort? = null,
    val diary: DiaryUiPort? = null,
    val authoring: PersonaAuthoringUiPort? = null,
    val currentPersona: CurrentPersonaUiPort? = null,
    val replySuggestions: ReplySuggestionsUiPort? = null,
    val gallery: PersonaGalleryUiPort? = null,
    val selection: PersonaSelectionUiPort? = null,
    val portraits: PersonaPortraitUiPort? = null,
    val modelPerformance: ModelPerformanceUiPort? = null,
    val pricing: PricingUiPort? = null,
)

@Composable
fun V6ToolsManagementScene(
    key: UiSceneId,
    owner: ToolsUiPort,
    onNavigate: (UiSceneId) -> UiSceneNavigationResult,
    onBack: () -> Unit,
    work: WorkConversationUiPort? = null,
    drafts: ConversationDraftUiPort? = null,
) {
    var revision by remember(owner) { mutableIntStateOf(0) }
    var servers by remember(owner) { mutableStateOf(emptyList<UiMcpServerSnapshot>()) }
    var healthResults by remember(owner) { mutableStateOf<Map<String, UiMcpHealth>>(emptyMap()) }
    var plugins by remember(owner) { mutableStateOf(emptyList<String>()) }
    var pluginStates by remember(owner) { mutableStateOf(emptyList<UiPluginLifecycle>()) }
    var recoverPluginId by remember(owner) { mutableStateOf<String?>(null) }
    var skills by remember(owner) { mutableStateOf(emptyList<UiSkillSnapshot>()) }
    var presets by remember(owner) { mutableStateOf(emptyList<UiPresetSkillSnapshot>()) }
    var github by remember(owner) { mutableStateOf(false) }
    var loading by remember(owner) { mutableStateOf(false) }
    var busy by remember(owner) { mutableStateOf(false) }
    var feedback by remember(key) { mutableStateOf<String?>(null) }
    var serverId by remember(key) { mutableStateOf("") }
    var endpoint by remember(key) { mutableStateOf("") }
    var skillUrl by remember(key) { mutableStateOf("") }
    var stdioArguments by remember(key) { mutableStateOf("") }
    var stdioWorkDir by remember(key) { mutableStateOf("") }
    var newSkillId by remember(key) { mutableStateOf("") }
    var newSkillName by remember(key) { mutableStateOf("") }
    var newSkillDescription by remember(key) { mutableStateOf("") }
    var newSkillInstructions by remember(key) { mutableStateOf("") }
    var editSkillId by remember(key) { mutableStateOf<String?>(null) }
    var selectedCapability by remember(key) { mutableStateOf<String?>(null) }
    var capabilityInstructions by remember(key) { mutableStateOf("") }
    var editSkillDocument by remember(key) { mutableStateOf("") }
    var githubToken by remember(key) { mutableStateOf("") }
    var disconnect by remember(key) { mutableStateOf<String?>(null) }
    var removeSkill by remember(key) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    val importSkillPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    val data = withContext(Dispatchers.IO) {
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                            null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "imported-skill.md"
                        val filename = name.substringAfterLast('/').substringAfterLast('\\')
                        require(filename.isNotBlank()) { "技能文件名无效" }
                        val bytes = resolver.openInputStream(uri)?.use { input ->
                            val buffer = ByteArray(8192)
                            val output = java.io.ByteArrayOutputStream()
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 2 * 1024 * 1024) {
                                    "技能文件超过 2 MiB 导入限制"
                                }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: error("无法打开所选技能文件")
                        filename to bytes
                    }
                    val result = withContext(Dispatchers.IO) {
                        owner.submit(ToolsUiCommand.ImportSkill(data.first, data.second))
                    }
                    feedback = when (result) {
                        is ToolsUiReceipt.Applied -> "所选技能已交原 Owner 导入"
                        is ToolsUiReceipt.Failed -> "技能导入失败：" + result.reason.name
                    }
                    revision++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    feedback = failure.message ?: "所选技能文件读取失败"
                } finally { busy = false }
            }
        }
    }
    LaunchedEffect(owner, revision) {
        loading = true
        try {
            withContext(Dispatchers.IO) {
                servers = owner.servers()
                plugins = owner.installedPluginIds().filterNot { it == "local-builtin" }
                pluginStates = owner.pluginLifecycleSnapshots()
                skills = owner.installedSkills()
                presets = owner.presetSkills()
                github = owner.githubConfigured()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: "工具目录读取失败" }
        finally { loading = false }
    }
    fun checkHealth(id: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val health = withContext(Dispatchers.IO) { owner.checkServerHealth(id) }
                healthResults = healthResults + (id to health)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                healthResults = healthResults + (id to UiMcpHealth.UNCONFIRMED)
                feedback = failure.message ?: "MCP 健康检查未能确认"
            } finally { busy = false }
        }
    }
    // Observe lifecycle changes initiated by remote agents while this page is visible.
    // This is an in-process registry poll, never a remote health/authorization subscription.
    LaunchedEffect(owner, key.value) {
        if (key.value != "tools.mcp") return@LaunchedEffect
        while (true) {
            delay(2_000L)
            if (loading || busy) continue
            val refreshed = try {
                withContext(Dispatchers.IO) { owner.servers() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { continue }
            if (refreshed != servers) {
                healthResults = emptyMap() // Never carry a previous ping through reconnect.
                servers = refreshed
            }
        }
    }
    fun execute(command: ToolsUiCommand) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { owner.submit(command) }
                feedback = when (response) {
                    is ToolsUiReceipt.Applied -> "工具配置已由 Owner 写入：" + response.ownerResult.orEmpty()
                    is ToolsUiReceipt.Failed -> "工具配置失败：" + response.reason.name
                }
                revision++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    val page = key.value
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(when(page) {
            "tools.mcp" -> "MCP 连接"
            "tools.github" -> "GitHub 工具"
            "tools.plugins" -> "插件与技能"
            "work.capabilities" -> "Work 可用工具能力"
            else -> "工具中心"
        }, style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        if (loading) SyNote("正在读取真实工具配置")
        feedback?.let { SyNote(it) }
        SyButton("刷新工具状态", { revision++ }, enabled = !busy && !loading,
            style = SyButtonStyle.SECONDARY)
        if (page == "route.tools" || page == "work.capabilities") {
            listOf("tools.mcp" to "MCP 服务","tools.plugins" to "插件与技能",
                "tools.github" to "GitHub").forEach { (id,title) ->
                SyButton(title, {
                    val result = onNavigate(UiSceneId(id))
                    if (result !is UiSceneNavigationResult.Changed) feedback = "无法打开：" + title
                }, style = SyButtonStyle.SECONDARY)
            }
            Text("已登记服务器：" + servers.size + " · 本地技能：" + skills.size)
            owner.capabilityCatalogue().forEach { ability ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text(ability.name + " · " + ability.family)
                    Text("访问：" + ability.access + " · 授权：" + ability.approval)
                    ability.requirements.forEach { Text(it) }
                    if (page == "work.capabilities" && work != null && drafts != null) {
                        SyButton("在 Work 中使用此能力", {
                            selectedCapability = ability.name
                        }, style = SyButtonStyle.SECONDARY)
                    }
                }
            }
            if (page == "work.capabilities" && selectedCapability != null &&
                work != null && drafts != null) {
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text("使用能力：" + selectedCapability)
                    SyInput(capabilityInstructions, { capabilityInstructions = it },
                        label = "需要完成的具体任务（由你确认后发送）", singleLine = false)
                    SyButton("填写到 Work 草稿", {
                        val identity = work.snapshot().identity
                        if (identity == null || capabilityInstructions.isBlank()) {
                            feedback = "当前没有有效 Work 会话，或缺少任务要求"
                        } else {
                            val target = selectedCapability
                            val message = "使用 ${target}：${capabilityInstructions.trim()}"
                            drafts.updateDraft(identity.sessionId, message)
                            val nav = onNavigate(UiSceneId("conversation.work"))
                            feedback = if (nav is UiSceneNavigationResult.Changed)
                                "已填写 Work 草稿，发送前仍可编辑；实际工具调用需经原 Work Owner 审批"
                            else "已保存 Work 草稿，但该会话页面暂不可达"
                            selectedCapability = null
                            capabilityInstructions = ""
                        }
                    }, enabled = capabilityInstructions.isNotBlank(),
                        style = SyButtonStyle.SECONDARY)
                    SyButton("取消", { selectedCapability = null },
                        style = SyButtonStyle.GHOST)
                }
            }
        }
        if (page == "tools.mcp") {
            SyNote("正在观察本地连接生命周期；远端在线状态请手动检查，工具授权逐次确认。")
            servers.forEach { server ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text(server.id + " · " + server.transport)
                    Text("登记：" + server.registration.name +
                        " · 连接：" + server.connection.name)
                    Text("Composer 选用：" + when (server.selection) {
                        UiToolSelection.SELECTED -> "已选用（仍需逐次授权）"
                        UiToolSelection.NOT_SELECTED -> "未选用"
                        UiToolSelection.UNKNOWN -> "当前不可确认"
                    })
                    Text("每次工具调用均须独立授权：" + server.authorization.name)
                    Text("声明工具：" + server.tools.joinToString(", ").take(1000))
                    healthResults[server.id]?.let { health ->
                        SyNote(when (health) {
                            UiMcpHealth.RESPONDED -> "MCP ping 已响应；这不代表工具已授权或持续在线"
                            UiMcpHealth.BUSY -> "MCP 服务正在停止或重连"
                            UiMcpHealth.NOT_CONNECTED -> "MCP 服务当前未登记连接"
                            UiMcpHealth.UNCONFIRMED -> "MCP 响应未确认；可检查网络、进程或协议"
                            UiMcpHealth.UNSUPPORTED -> "当前工具 Owner 不支持主动健康检查"
                        })
                    }
                    SyButton("主动检查服务状态", { checkHealth(server.id) },
                        enabled = !busy && !loading && server.registration == UiToolRegistration.REGISTERED,
                        style = SyButtonStyle.SECONDARY)
                    SyButton("断开连接", { disconnect = server.id },
                        enabled = !busy && !loading, style = SyButtonStyle.DANGER)
                }
            }
            SyInput(serverId, { serverId = it }, label = "服务器 ID")
            SyInput(endpoint, { endpoint = it }, label = "HTTPS 地址")
            SyButton("添加 HTTP MCP", {
                execute(ToolsUiCommand.ConnectHttp(serverId.trim(), endpoint.trim()))
            }, enabled = serverId.isNotBlank() && endpoint.startsWith("https://") && !busy)
            SyInput(stdioArguments, { stdioArguments = it },
                label = "本地 STDIO 命令和参数（每行一个参数）", singleLine = false)
            SyInput(stdioWorkDir, { stdioWorkDir = it },
                label = "工作目录（可选；需由原 Owner 检验）")
            SyNote("STDIO 服务只允许由原工具 Owner 审核工作目录和可执行文件；UI 不调用 Shell。")
            SyButton("添加本机 STDIO MCP", {
                val argv = stdioArguments.lines().map(String::trim)
                    .filter(String::isNotEmpty)
                if (argv.isNotEmpty())
                    execute(ToolsUiCommand.ConnectStdio(serverId.trim(), argv,
                        stdioWorkDir.trim().takeIf(String::isNotEmpty)))
            }, enabled = serverId.isNotBlank() && stdioArguments.isNotBlank() && !busy)
        }
        if (page == "tools.github") {
            Text(if (github) "GitHub 已配置" else "尚未配置 GitHub")
            androidx.compose.material3.OutlinedTextField(githubToken, { githubToken = it },
                label = { Text("GitHub Token（临时输入）") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            SyButton("设置 GitHub 凭据", {
                val secret = githubToken
                githubToken = ""
                execute(ToolsUiCommand.ConfigureGitHub(secret))
            }, enabled = githubToken.isNotBlank() && !busy)
            SyButton("清除 GitHub 凭据", { disconnect = "github-credential" },
                style = SyButtonStyle.DANGER, enabled = github && !busy)
            SyInput(skillUrl, { skillUrl = it }, label = "仓库技能地址")
            SyButton("安装 GitHub 技能", {
                execute(ToolsUiCommand.InstallGitHubSkill(skillUrl.trim()))
            }, enabled = skillUrl.startsWith("https://") && !busy)
        }
        if (page == "tools.plugins") {
            Text("插件真实生命周期")
            pluginStates.forEach { plugin ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text(plugin.id + " · " + plugin.state.name)
                    if (plugin.state == UiPluginLifecycleState.FAILED) {
                        SyNote("插件失败，旧工具不会被当作可用；需要明确恢复")
                        if (plugin.id != "android-runtime" && plugin.id != "android-device") {
                            SyButton("重新初始化失败插件", { recoverPluginId = plugin.id },
                                enabled = !busy, style = SyButtonStyle.SECONDARY)
                        } else {
                            SyNote("平台资源插件须重启运行环境")
                        }
                    }
                }
            }
            if (pluginStates.isEmpty()) plugins.forEach { Text(it) }
            Text("已安装技能")
            skills.forEach { skill ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text(skill.displayName)
                    Text(skill.description)
                    SyButton("读取并编辑技能", {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                try {
                                    val document = withContext(Dispatchers.IO) {
                                        owner.readSkillDocument(skill.name)
                                    }
                                    editSkillId = skill.name
                                    editSkillDocument = document
                                    feedback = "技能源码已读取，编辑后需要明确保存"
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) {
                                    feedback = failure.message ?: "无法读取技能文档"
                                } finally { busy = false }
                            }
                        }
                    }, enabled = !busy, style = SyButtonStyle.SECONDARY)
                    SySwitch("允许模型调用", skill.modelInvocable, {
                        execute(ToolsUiCommand.SetSkillModelInvocable(skill.name, it))
                    }, enabled = !busy)
                    SyButton("移除技能", { removeSkill = skill.name },
                        enabled = !busy, style = SyButtonStyle.DANGER)
                }
            }
            SyButton("从本地文件导入技能", {
                importSkillPicker.launch(arrayOf("text/*", "application/json", "application/octet-stream"))
            }, enabled = !busy, style = SyButtonStyle.SECONDARY)
            Text("新建本地技能", style = MaterialTheme.typography.titleMedium)
            SyInput(newSkillId, { newSkillId = it }, label = "技能 ID")
            SyInput(newSkillName, { newSkillName = it }, label = "技能名称")
            SyInput(newSkillDescription, { newSkillDescription = it },
                label = "用途简述", singleLine = false)
            SyInput(newSkillInstructions, { newSkillInstructions = it },
                label = "技能执行指令", singleLine = false)
            SyButton("创建本地技能", {
                execute(ToolsUiCommand.CreateSkill(newSkillId.trim(), newSkillName.trim(),
                    newSkillDescription.trim(), newSkillInstructions))
            }, enabled = !busy && newSkillId.isNotBlank() && newSkillName.isNotBlank() &&
                newSkillInstructions.isNotBlank())
            editSkillId?.let { id ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text("正在编辑：" + id, style = MaterialTheme.typography.titleMedium)
                    SyInput(editSkillDocument, { editSkillDocument = it },
                        label = "技能文档内容", singleLine = false)
                    SyButton("保存技能文档", {
                        execute(ToolsUiCommand.UpdateSkill(id, editSkillDocument))
                        editSkillId = null
                    }, enabled = !busy && editSkillDocument.isNotBlank(),
                        style = SyButtonStyle.SECONDARY)
                    SyButton("关闭编辑", { editSkillId = null },
                        style = SyButtonStyle.GHOST)
                }
            }
            Text("预置技能")
            presets.forEach { preset ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text(preset.title)
                    Text(preset.description)
                    SyButton(if (preset.installed) "已安装" else "安装", {
                        execute(ToolsUiCommand.InstallPreset(preset.id))
                    }, enabled = !preset.installed && !busy)
                }
            }
        }
    }
    recoverPluginId?.let { id -> AlertDialog(
        onDismissRequest = { recoverPluginId = null },
        title = { Text("确认恢复失败插件") },
        text = { Text("将清理旧实例并重新安装：" + id + "。不会重放先前失败的工具调用。") },
        confirmButton = { TextButton(onClick = {
            recoverPluginId = null
            execute(ToolsUiCommand.RecoverFailedPlugin(id))
        }) { Text("确认恢复") } },
        dismissButton = { TextButton(onClick = { recoverPluginId = null }) { Text("取消") } },
    ) }
    disconnect?.let { id -> AlertDialog(onDismissRequest = { disconnect = null },
        title = { Text("确认撤销连接") },
        text = { Text("将交由工具 Owner 修改本地凭据或连接状态。") },
        confirmButton = { TextButton(onClick = {
            disconnect = null
            execute(if (id == "github-credential") ToolsUiCommand.ClearGitHub
                else ToolsUiCommand.Disconnect(id))
        }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { disconnect = null }) { Text("取消") } })
    }
    removeSkill?.let { id -> AlertDialog(onDismissRequest = { removeSkill = null },
        title = { Text("确认删除本地技能") },
        text = { Text(id) },
        confirmButton = { TextButton(onClick = {
            removeSkill = null
            execute(ToolsUiCommand.RemoveSkill(id))
        }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { removeSkill = null }) { Text("取消") } })
    }
}

/** Workspace browsing uses FilesUiPort for both listing and bounded previews. */
@Composable
fun V6WorkspaceScene(owner: FilesUiPort, previewFirst: Boolean, onBack: () -> Unit) {
    var files by remember(owner) { mutableStateOf(emptyList<LocalWorkspaceFile>()) }
    var selected by remember(owner) { mutableStateOf<String?>(null) }
    var preview by remember(owner) { mutableStateOf<LocalWorkspaceFilePreview?>(null) }
    var filter by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportPath by remember { mutableStateOf<String?>(null) }
    var exportAction by remember { mutableStateOf("save") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val path = exportPath
        val action = exportAction
        if (uri == null) {
            feedback = "已取消文件导出"
        } else if (path != null && !exporting) {
            exporting = true
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                            owner.exportWorkspaceFile(path, stream)
                        } ?: error("无法打开用户选择的文件保存位置")
                    }
                    feedback = "已保存原文件（${bytes} 字节），内容来自工作区 Files Owner"
                    val mime = context.contentResolver.getType(uri)
                        ?: java.net.URLConnection.guessContentTypeFromName(path)
                        ?: "application/octet-stream"
                    val intent = when (action) {
                        "share" -> Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        "open" -> Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        else -> null
                    }
                    if (intent != null) {
                        try {
                            context.startActivity(if (action == "share")
                                Intent.createChooser(intent, "分享工作区文件") else intent)
                        } catch (failure: Exception) {
                            feedback = "文件已保存，但无法打开外部应用：" +
                                (failure.message ?: "没有支持该文件类型的应用")
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    feedback = failure.message ?: "文件保存失败；请检查目标位置"
                } finally {
                    exporting = false
                    exportPath = null
                }
            }
        }
    }
    fun exportFile(path: String, action: String) {
        if (exporting) return
        exportPath = path
        exportAction = action
        val name = path.substringAfterLast('/').ifBlank { "workspace-file" }
        try { exportLauncher.launch(name) }
        catch (failure: Exception) {
            feedback = failure.message ?: "无法打开 Android 文件选择器"
            exportPath = null
        }
    }
    LaunchedEffect(owner, refresh) {
        loading = true
        try { files = withContext(Dispatchers.IO) { owner.workspaceFiles() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: "文件列表不可读取" }
        finally { loading = false }
    }
    LaunchedEffect(owner, selected, refresh) {
        preview = null
        val path = selected ?: return@LaunchedEffect
        try { preview = withContext(Dispatchers.IO) { owner.previewWorkspaceFile(path) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: "文件预览失败" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (previewFirst) "工作区文件预览" else "工作区",
            style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        SyInput(filter, { filter = it }, label = "搜索本地文件")
        SyButton("刷新文件目录", { refresh++ }, enabled = !loading,
            style = SyButtonStyle.SECONDARY)
        feedback?.let { SyNote(it) }
        if (loading) SyNote("正在读取工作区")
        if (exporting) SyNote("正在将所选文件写入 Android 授权的保存位置")
        files.filter { it.path.contains(filter, ignoreCase = true) }.forEach { file ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text(file.path)
                Text("字节：" + file.bytes)
                SyButton("查看内容", { selected = file.path },
                    style = SyButtonStyle.SECONDARY)
                SyButton("保存原文件", { exportFile(file.path, "save") },
                    enabled = !exporting && !loading, style = SyButtonStyle.SECONDARY)
                SyButton("保存并分享", { exportFile(file.path, "share") },
                    enabled = !exporting && !loading, style = SyButtonStyle.GHOST)
                SyButton("保存并用外部应用打开", { exportFile(file.path, "open") },
                    enabled = !exporting && !loading, style = SyButtonStyle.GHOST)
            }
        }
        selected?.let { path ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text("预览：" + path)
                preview?.let { item ->
                    SelectionContainer { Text(item.text ?: "此文件没有可预览的文本") }
                    if (item.truncated) SyNote("源文件过长，仅显示 Owner 允许的部分")
                }
                SyButton("关闭预览", { selected = null }, style = SyButtonStyle.GHOST)
            }
        }
    }
}

/** Real media creation uses paid Owner requests only after an explicit confirmation. */
@Composable
fun V6MediaScene(media: MediaUiPort, settings: SettingsUiPort,
    sessionId: String?, creation: Boolean, onBack: () -> Unit) {
    val config by settings.state.collectAsStateWithLifecycle(initialValue = settings.initialState)
    val offers = remember(config.modelProfiles) { media.offers(config.modelProfiles) }
    var historyLimit by rememberSaveable(sessionId) { mutableIntStateOf(media.pageSize) }
    val entries by remember(media, sessionId, historyLimit) { media.observe(sessionId, historyLimit) }
        .collectAsStateWithLifecycle(initialValue = LocalMediaPage())
    val storageError by media.storageError.collectAsStateWithLifecycle()
    var chosenKind by rememberSaveable { mutableStateOf(LocalMediaKind.IMAGE.name) }
    val kind = LocalMediaKind.valueOf(chosenKind)
    val candidates = offers.filter { it.kind == kind }
    var picked by rememberSaveable(chosenKind) { mutableStateOf("") }
    var imageText by rememberSaveable { mutableStateOf("") }
    var videoText by rememberSaveable { mutableStateOf("") }
    var speechText by rememberSaveable { mutableStateOf("") }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showArchive by rememberSaveable { mutableStateOf(!creation) }
    var audioRouteId by remember { mutableStateOf<String?>(null) }
    var frozenRequest by remember { mutableStateOf<MediaUiCommand?>(null) }
    val prompt = when(kind) {
        LocalMediaKind.IMAGE -> imageText
        LocalMediaKind.VIDEO -> videoText
        LocalMediaKind.SPEECH -> speechText
        LocalMediaKind.TRANSCRIBE -> ""
    }
    var ratio by remember { mutableStateOf("1:1") }
    var voice by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf(emptyList<LocalMediaVoice>()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val offer = candidates.firstOrNull { it.id == picked } ?: candidates.firstOrNull()
    LaunchedEffect(offer) {
        voices = emptyList()
        if (offer?.kind == LocalMediaKind.SPEECH) {
            try {
                voices = withContext(Dispatchers.IO) { media.voices(offer) }
                if (voices.none { it.id == voice }) voice = voices.firstOrNull()?.id.orEmpty()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message ?: "音色读取失败" }
        }
    }
    fun submit(command: MediaUiCommand) {
        if (pending) return
        pending = true
        scope.launch {
            try {
                notice = when (val result = withContext(Dispatchers.IO) { media.submit(command) }) {
                    is MediaUiReceipt.CreationAdmitted ->
                        "媒体任务已受理：" + result.job.phase.name + "；尚未生成完成"
                    is MediaUiReceipt.VideoAdmitted ->
                        "视频任务已受理：" + result.task.phase.name + "；尚未生成完成"
                    MediaUiReceipt.Applied -> "本地操作已提交，以最新媒体记录为准"
                    is MediaUiReceipt.Failed -> result.message ?: "媒体操作失败，供应商可能仍产生费用"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message ?: "媒体操作异常" }
            finally { pending = false }
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val original = offers.firstOrNull { it.id == audioRouteId && it.kind == LocalMediaKind.TRANSCRIBE }
        audioRouteId = null
        if (uri != null && original != null && !sessionId.isNullOrBlank()) {
            frozenRequest = MediaUiCommand.Transcribe(original, uri.toString(), sessionId)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (creation) "媒体创作" else "媒体成果", style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        notice?.let { SyNote(it) }
        storageError?.let { SyNote("媒体存储异常：" + it) }
        if (storageError != null) SyButton("重新读取媒体存储", {
            submit(MediaUiCommand.RetryStorage)
        }, enabled = !pending)
        if (creation) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(LocalMediaKind.IMAGE to "图片", LocalMediaKind.VIDEO to "视频",
                    LocalMediaKind.SPEECH to "配音", LocalMediaKind.TRANSCRIBE to "转写").forEach { (mode, label) ->
                    SyButton(label, {
                        if (!pending) { chosenKind = mode.name; showModels = false; notice = null }
                    }, style = if (kind == mode) SyButtonStyle.PRIMARY else SyButtonStyle.GHOST,
                        enabled = !pending, compact = true)
                }
            }
            if (offer == null) SyNote("该类型没有可用的创作模型，请配置官方 API 账户。")
            else {
                SyButton("模型：" + offer.label + " ▾", { showModels = !showModels },
                    enabled = !pending, style = SyButtonStyle.GHOST, compact = true)
                if (showModels) candidates.forEach { alternative ->
                    SyButton(alternative.label + " · " +
                            (alternative.profile.displayName ?: alternative.profile.model),
                        { picked = alternative.id; showModels = false },
                        style = if (alternative.id == offer.id) SyButtonStyle.PRIMARY else SyButtonStyle.GHOST,
                        enabled = !pending, compact = true)
                }
                if (kind != LocalMediaKind.TRANSCRIBE) {
                    SyInput(prompt, { value ->
                        when (kind) {
                            LocalMediaKind.IMAGE -> imageText = value
                            LocalMediaKind.VIDEO -> videoText = value
                            LocalMediaKind.SPEECH -> speechText = value
                            LocalMediaKind.TRANSCRIBE -> Unit
                        }
                    }, label = if (kind == LocalMediaKind.SPEECH) "朗读正文" else "创作描述",
                        singleLine = false, enabled = !pending)
                }
                if (kind == LocalMediaKind.IMAGE) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1:1", "16:9", "9:16").forEach { aspect ->
                        SyButton(aspect, { ratio = aspect },
                            style = if (ratio == aspect) SyButtonStyle.PRIMARY else SyButtonStyle.GHOST,
                            enabled = !pending, compact = true)
                    }
                }
                if (kind == LocalMediaKind.SPEECH) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        voices.forEach { option ->
                            SyButton(option.name, { voice = option.id },
                                style = if (voice == option.id) SyButtonStyle.PRIMARY else SyButtonStyle.GHOST,
                                enabled = !pending, compact = true)
                        }
                    }
                }
                if (kind == LocalMediaKind.TRANSCRIBE) {
                    Text("选择音频文件，文字结果将显示在当前聊天。",
                        style = MaterialTheme.typography.bodySmall)
                    SyButton("选择音频并转写", {
                        audioRouteId = offer.id
                        audioPicker.launch(arrayOf("audio/*"))
                    }, enabled = !pending && !sessionId.isNullOrBlank())
                } else {
                    SyButton(when(kind) {
                        LocalMediaKind.IMAGE -> "生成图片"
                        LocalMediaKind.VIDEO -> "生成视频"
                        LocalMediaKind.SPEECH -> "生成配音"
                        LocalMediaKind.TRANSCRIBE -> "转写"
                    }, {
                        val sid = sessionId
                        if (!sid.isNullOrBlank()) frozenRequest = when (kind) {
                            LocalMediaKind.IMAGE -> MediaUiCommand.Image(offer, imageText, ratio, sid)
                            LocalMediaKind.VIDEO -> MediaUiCommand.Video(offer, videoText, sid)
                            LocalMediaKind.SPEECH -> MediaUiCommand.Speech(offer, speechText, voice, sid)
                            LocalMediaKind.TRANSCRIBE -> null
                        }
                    }, enabled = !pending && prompt.isNotBlank() && !sessionId.isNullOrBlank() &&
                        (kind != LocalMediaKind.SPEECH || voices.any { it.id == voice }))
                }
                Text("独立创作 API 可能另行计费，请确认后提交。",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        SyButton(if (showArchive) "收起历史成果 ▴" else "查看历史成果 ▾",
            { showArchive = !showArchive }, style = SyButtonStyle.GHOST, compact = true)
        if (showArchive) {
        entries.jobs.forEach { job ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text(job.kind + " · " + job.phase.name + " · " + job.model)
                job.error?.let { SyNote(it) }
                SyButton("移除任务提示", { submit(MediaUiCommand.DismissJob(job.id)) },
                    enabled = !pending, style = SyButtonStyle.GHOST)
            }
        }
        entries.videos.forEach { video ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text("视频：" + video.phase.name + " · " + video.deliveryPhase.name)
                video.lastError?.let { SyNote(it) }
                val matched = offers.firstOrNull { media.canRefresh(video, it) }
                if (matched != null) SyButton("用原账户刷新", {
                    if (pending) return@SyButton
                    pending = true
                    scope.launch {
                        try {
                            val eligible = withContext(Dispatchers.IO) {
                                media.hasMatchingCredential(matched, video.credentialSignature)
                            }
                            if (eligible) {
                                val result = withContext(Dispatchers.IO) {
                                    media.submit(MediaUiCommand.PollVideo(matched, video.id))
                                }
                                notice = when(result) {
                                    is MediaUiReceipt.Failed -> result.message ?: "刷新失败"
                                    else -> "已查询原供应商，请查看真实任务状态"
                                }
                            } else notice = "原账户凭据已改变，无法用新凭据查询原付费任务"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { notice = failure.message ?: "查询失败" }
                        finally { pending = false }
                    }
                }, enabled = !pending)
                if (video.deliveryPhase == LocalMediaDeliveryPhase.FAILED &&
                    video.phase == LocalMediaVideoPhase.SUCCEEDED) SyButton("重试本地保存", {
                    submit(MediaUiCommand.RetryLocalVideoSave(video.id))
                }, enabled = !pending)
            }
        }
        entries.results.forEach { result ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text(result.kind + " · " + result.model)
                result.description?.let { Text(it) }
                val asset = sessionId?.let { media.assetForSession(result.id, it) }
                asset?.textOutput?.let { Text(it) }
                val localKind = asset?.kind
                if (asset?.localPath != null && localKind != null && sessionId != null) {
                    // Snapshot the cross-module nullable kind for Kotlin smart-cast stability.
                    // Only session-scoped media assets enter a native local-file decoder.
                    V6DocumentMediaNode(
                        UiDocumentNode.Media("archive-" + result.id, result.id, localKind,
                            "预览并导出本地媒体"), sessionId, media)
                } else if (asset?.localPath != null) {
                    Text("本地媒体类型尚未通过 Owner 标注，无法安全预览")
                } else {
                    Text("此会话尚无授权的本地文件；远端地址不会自动下载")
                }
                SyButton("删除成果", { deletion = result.id },
                    enabled = !pending, style = SyButtonStyle.DANGER)
            }
        }
        if (entries.unavailable) SyNote("媒体记录暂不可读取")
        if (entries.hasMore) SyButton("加载更早成果", {
            historyLimit = (historyLimit + media.pageSize).coerceAtMost(10_000)
        }, enabled = !pending && historyLimit < 10_000, style = SyButtonStyle.GHOST, compact = true)
        }
    }
    frozenRequest?.let { fixed -> AlertDialog(onDismissRequest = { frozenRequest = null },
        title = { Text("确认媒体创作请求") },
        text = { Text("将调用专业创作接口，可能单独计费。断线后不要盲目重复提交。") },
        confirmButton = { TextButton(onClick = {
            frozenRequest = null
            submit(fixed)
        }, enabled = !pending) { Text("确认提交") } },
        dismissButton = { TextButton(onClick = { frozenRequest = null }) { Text("取消") } })
    }
    deletion?.let { id -> AlertDialog(onDismissRequest = { deletion = null },
        title = { Text("确认删除媒体成果") },
        text = { Text("删除后此成果可能无法恢复。") },
        confirmButton = { TextButton(onClick = {
            deletion = null
            submit(MediaUiCommand.RemoveResult(id))
        }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } })
    }
}
