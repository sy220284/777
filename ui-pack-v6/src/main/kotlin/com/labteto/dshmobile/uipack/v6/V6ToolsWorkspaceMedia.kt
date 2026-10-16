package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
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
)

@Composable
fun V6ToolsManagementScene(
    key: UiSceneId,
    owner: ToolsUiPort,
    onNavigate: (UiSceneId) -> UiSceneNavigationResult,
    onBack: () -> Unit,
) {
    var revision by remember(owner) { mutableIntStateOf(0) }
    var servers by remember(owner) { mutableStateOf(emptyList<UiMcpServerSnapshot>()) }
    var plugins by remember(owner) { mutableStateOf(emptyList<String>()) }
    var skills by remember(owner) { mutableStateOf(emptyList<UiSkillSnapshot>()) }
    var presets by remember(owner) { mutableStateOf(emptyList<UiPresetSkillSnapshot>()) }
    var github by remember(owner) { mutableStateOf(false) }
    var loading by remember(owner) { mutableStateOf(false) }
    var busy by remember(owner) { mutableStateOf(false) }
    var feedback by remember(key) { mutableStateOf<String?>(null) }
    var serverId by remember(key) { mutableStateOf("") }
    var endpoint by remember(key) { mutableStateOf("") }
    var skillUrl by remember(key) { mutableStateOf("") }
    var githubToken by remember(key) { mutableStateOf("") }
    var disconnect by remember(key) { mutableStateOf<String?>(null) }
    var removeSkill by remember(key) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(owner, revision) {
        loading = true
        try {
            withContext(Dispatchers.IO) {
                servers = owner.servers()
                plugins = owner.installedPluginIds().filterNot { it == "local-builtin" }
                skills = owner.installedSkills()
                presets = owner.presetSkills()
                github = owner.githubConfigured()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: "工具目录读取失败" }
        finally { loading = false }
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
                }
            }
        }
        if (page == "tools.mcp") {
            servers.forEach { server ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text(server.id + " · " + server.transport)
                    Text("登记：" + server.registration.name +
                        " · 连接：" + server.connection.name)
                    Text("每次工具调用均须独立授权：" + server.authorization.name)
                    Text("声明工具：" + server.tools.joinToString(", ").take(1000))
                    SyButton("断开连接", { disconnect = server.id },
                        enabled = !busy && !loading, style = SyButtonStyle.DANGER)
                }
            }
            SyInput(serverId, { serverId = it }, label = "服务器 ID")
            SyInput(endpoint, { endpoint = it }, label = "HTTPS 地址")
            SyButton("添加 HTTP MCP", {
                execute(ToolsUiCommand.ConnectHttp(serverId.trim(), endpoint.trim()))
            }, enabled = serverId.isNotBlank() && endpoint.startsWith("https://") && !busy)
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
            Text("已安装插件")
            plugins.forEach { Text(it) }
            Text("已安装技能")
            skills.forEach { skill ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text(skill.displayName)
                    Text(skill.description)
                    SySwitch("允许模型调用", skill.modelInvocable, {
                        execute(ToolsUiCommand.SetSkillModelInvocable(skill.name, it))
                    }, enabled = !busy)
                    SyButton("移除技能", { removeSkill = skill.name },
                        enabled = !busy, style = SyButtonStyle.DANGER)
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
        files.filter { it.path.contains(filter, ignoreCase = true) }.forEach { file ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text(file.path)
                Text("字节：" + file.bytes)
                SyButton("查看内容", { selected = file.path },
                    style = SyButtonStyle.SECONDARY)
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
    val entries by remember(media) { media.observe(null, media.pageSize * 4) }
        .collectAsStateWithLifecycle(initialValue = LocalMediaPage())
    val storageError by media.storageError.collectAsStateWithLifecycle()
    var picked by remember(offers) { mutableStateOf(offers.firstOrNull()?.id) }
    var prompt by remember { mutableStateOf("") }
    var ratio by remember { mutableStateOf("1:1") }
    var voice by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf(emptyList<LocalMediaVoice>()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val offer = offers.firstOrNull { it.id == picked }
    LaunchedEffect(offer) {
        voices = emptyList()
        if (offer?.kind == LocalMediaKind.SPEECH) {
            try { voices = withContext(Dispatchers.IO) { media.voices(offer) } }
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
            Text("模型提供方实际支持的媒体类型")
            offers.forEach { candidate ->
                SyButton(candidate.label + " · " + candidate.kind.name,
                    { picked = candidate.id },
                    style = if (picked == candidate.id) SyButtonStyle.PRIMARY else SyButtonStyle.SECONDARY)
            }
            SyInput(prompt, { prompt = it },
                label = if (offer?.kind == LocalMediaKind.SPEECH) "朗读文本" else "媒体创作描述",
                singleLine = false)
            if (offer?.kind == LocalMediaKind.IMAGE)
                SyInput(ratio, { ratio = it.take(16) }, label = "画幅比例")
            if (offer?.kind == LocalMediaKind.SPEECH)
                voices.forEach { candidate ->
                    SyButton(candidate.name, { voice = candidate.id },
                        style = if (candidate.id == voice) SyButtonStyle.PRIMARY else SyButtonStyle.GHOST)
                }
            SyButton("检查并确认媒体创作", { confirmation = true },
                enabled = !pending && offer != null && prompt.isNotBlank() &&
                    !sessionId.isNullOrBlank() && offer.kind != LocalMediaKind.TRANSCRIBE &&
                    (offer.kind != LocalMediaKind.SPEECH || voices.any { it.id == voice }))
        }
        entries.jobs.forEach { job ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text(job.kind.name + " · " + job.phase.name + " · " + job.model)
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
                Text(if (asset?.localPath != null) "设备本地已保存" else "尚无此会话可访问的本地资产")
                SyButton("删除成果", { deletion = result.id },
                    enabled = !pending, style = SyButtonStyle.DANGER)
            }
        }
        if (entries.unavailable) SyNote("媒体记录暂不可读取")
        if (entries.hasMore) SyNote("更多旧记录尚未全部加载")
    }
    if (confirmation) AlertDialog(onDismissRequest = { confirmation = false },
        title = { Text("确认发起媒体模型请求") },
        text = { Text("该操作可能产生实际费用；即使响应断开，服务商也可能继续执行。") },
        confirmButton = { TextButton(onClick = {
            confirmation = false
            val target = offer
            val sid = sessionId
            if (target != null && !sid.isNullOrBlank()) when(target.kind) {
                LocalMediaKind.IMAGE -> submit(MediaUiCommand.Image(target, prompt, ratio, sid))
                LocalMediaKind.VIDEO -> submit(MediaUiCommand.Video(target, prompt, sid))
                LocalMediaKind.SPEECH -> submit(MediaUiCommand.Speech(target, prompt, voice, sid))
                LocalMediaKind.TRANSCRIBE -> Unit
            }
        }) { Text("确认计费请求") } },
        dismissButton = { TextButton(onClick = { confirmation = false }) { Text("取消") } })
    deletion?.let { id -> AlertDialog(onDismissRequest = { deletion = null },
        title = { Text("确认删除媒体成果") },
        text = { Text("删除后此成果可能无法恢复。") },
        confirmButton = { TextButton(onClick = {
            deletion = null
            submit(MediaUiCommand.RemoveResult(id))
        }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } })
}
