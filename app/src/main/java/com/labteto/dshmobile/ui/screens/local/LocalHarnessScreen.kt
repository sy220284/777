package com.labteto.dshmobile.ui.screens.local

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.LocalApproval
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

/** Default Android 16 home: local Harness first, remote transports live in the left drawer. */
@Composable
fun LocalHarnessScreen(
    onOpenLan: () -> Unit,
    onOpenRelay: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LocalHarnessViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var editingConfig by rememberSaveable { mutableStateOf(false) }
    var showDiagnostic by rememberSaveable { mutableStateOf(false) }
    var showEnvironment by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = state.pendingApproval != null) { viewModel.deny() }
    BackHandler(enabled = state.pendingQuestion != null) { viewModel.answerQuestion("用户取消了问题") }
    BackHandler(enabled = editingConfig && state.configured) { editingConfig = false }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            LocalModeDrawer(
                onLocal = { scope.launch { drawerState.close() } },
                onLan = {
                    scope.launch { drawerState.close() }
                    onOpenLan()
                },
                onRelay = {
                    scope.launch { drawerState.close() }
                    onOpenRelay()
                },
                onNetworkDiagnostic = {
                    scope.launch { drawerState.close() }
                    showDiagnostic = true
                },
                onEnvironment = {
                    scope.launch { drawerState.close() }
                    showEnvironment = true
                },
                onSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
            )
        },
    ) {
        when {
            state.loading -> LoadingScreen()
            !state.configured || editingConfig -> LocalConfiguration(
                state = state,
                canCancel = state.configured,
                onOpenMenu = { scope.launch { drawerState.open() } },
                onCancel = { editingConfig = false },
                onSave = { key, model, base ->
                    viewModel.configure(key, model, base)
                    editingConfig = false
                },
                onClearCredential = viewModel::clearCredential,
            )
            else -> LocalChat(
                state = state,
                onOpenMenu = { scope.launch { drawerState.open() } },
                onConfigure = { editingConfig = true },
                onSend = viewModel::send,
                onImportAttachment = viewModel::importAttachment,
                onStop = viewModel::stop,
                onNewSession = viewModel::newSession,
                onPlanModeChange = viewModel::setPlanMode,
                onSwitchSession = viewModel::switchSession,
                onApprove = viewModel::approve,
                onDeny = viewModel::deny,
                onAnswerQuestion = viewModel::answerQuestion,
            )
        }
    }

    if (showDiagnostic) {
        NetworkDiagnosticDialog(
            onDismiss = { showDiagnostic = false },
            diagnose = viewModel::diagnoseNetwork,
        )
    }

    if (showEnvironment) {
        EnvironmentInfoDialog(
            text = viewModel.environmentInfo(),
            onDismiss = { showEnvironment = false },
        )
    }
}

@Composable
private fun LocalModeDrawer(
    onLocal: () -> Unit,
    onLan: () -> Unit,
    onRelay: () -> Unit,
    onNetworkDiagnostic: () -> Unit,
    onEnvironment: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = DsTheme.colors
    ModalDrawerSheet(
        drawerContainerColor = colors.bgLayer1,
        modifier = Modifier.safeDrawingPadding(),
    ) {
        Column(
            Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.large),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Text("DSH Mobile", style = DsType.large20, color = colors.labelPrimary)
            Text(
                "默认在手机本机运行。向右滑动可随时打开这里。",
                style = DsType.small13,
                color = colors.labelTertiary,
            )
            Spacer(Modifier.height(DsSpacing.small))
            NavigationDrawerItem(
                label = { Text("本机 Harness") },
                selected = true,
                onClick = onLocal,
            )
            NavigationDrawerItem(
                label = { Text("局域网 Harness") },
                selected = false,
                onClick = onLan,
            )
            NavigationDrawerItem(
                label = { Text("中继连接") },
                selected = false,
                onClick = onRelay,
            )
            HorizontalDivider()
            NavigationDrawerItem(
                label = { Text("网络诊断") },
                selected = false,
                onClick = onNetworkDiagnostic,
            )
            NavigationDrawerItem(
                label = { Text("环境能力") },
                selected = false,
                onClick = onEnvironment,
            )
            NavigationDrawerItem(
                label = { Text("设置") },
                selected = false,
                onClick = onSettings,
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = DsTheme.colors.brandPrimary)
    }
}

@Composable
private fun LocalConfiguration(
    state: LocalHarnessState,
    canCancel: Boolean,
    onOpenMenu: () -> Unit,
    onCancel: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onClearCredential: () -> Unit,
) {
    val colors = DsTheme.colors
    var apiKey by remember { mutableStateOf("") }
    var model by rememberSaveable(state.model) { mutableStateOf(state.model) }
    var baseUrl by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(DsSpacing.xlarge),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsButton("菜单", onOpenMenu, variant = DsButtonVariant.Ghost)
            Text("本机 Harness", style = DsType.large20, color = colors.labelPrimary)
            if (canCancel) {
                DsButton("取消", onCancel, variant = DsButtonVariant.Ghost)
            } else {
                Spacer(Modifier.size(56.dp))
            }
        }

        DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Text("手机直接执行", style = DsType.base16Strong, color = colors.labelPrimary)
            Text(
                "模型、文件、网页、命令、技能和子代理都在手机侧组织执行。局域网与中继已经移到侧边菜单。",
                style = DsType.std14,
                color = colors.labelSecondary,
            )
            Text(
                "工作区：${state.workspacePath}",
                style = DsType.xsmall12.copy(fontFamily = FontFamily.Monospace),
                color = colors.labelTertiary,
            )
        }

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (state.configured) "新的 API 密钥" else "DeepSeek API 密钥") },
            supportingText = {
                Text(
                    if (state.configured) "留空可保留现有密钥；新密钥仍由安卓系统密钥库加密。"
                    else "密钥由安卓系统密钥库加密，不写入会话或工作区。",
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )

        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
            Text("模型", style = DsType.std14Strong, color = colors.labelPrimary)
            ModelChoice("deepseek-chat", "DeepSeek Chat｜工具执行", model) { model = it }
            ModelChoice("deepseek-reasoner", "DeepSeek Reasoner｜深度推理", model) { model = it }
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("接口地址") },
            supportingText = { Text("兼容 OpenAI 聊天补全协议的服务也可使用。") },
            singleLine = true,
        )

        state.error?.let { Text(it, style = DsType.small13, color = colors.error) }

        DsButton(
            text = "保存并进入本机 Harness",
            onClick = { onSave(apiKey, model, baseUrl) },
            modifier = Modifier.fillMaxWidth(),
            enabled = (state.configured || apiKey.isNotBlank()) && baseUrl.isNotBlank(),
        )
        if (state.configured) {
            DsButton(
                text = "清除本机模型密钥",
                onClick = onClearCredential,
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Danger,
            )
        }
    }
}

@Composable
private fun ModelChoice(id: String, label: String, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected == id, onClick = { onSelect(id) })
            .padding(vertical = DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected == id, onClick = { onSelect(id) })
        Text(label, style = DsType.std14, color = DsTheme.colors.labelPrimary)
    }
}

@Composable
private fun LocalChat(
    state: LocalHarnessState,
    onOpenMenu: () -> Unit,
    onConfigure: () -> Unit,
    onSend: (String, List<LocalImportedAttachment>) -> Unit,
    onImportAttachment: suspend (android.net.Uri) -> LocalImportedAttachment,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    onPlanModeChange: (Boolean) -> Unit,
    onSwitchSession: (String) -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAnswerQuestion: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var showSessions by rememberSaveable { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    val attachments = remember { mutableStateListOf<LocalImportedAttachment>() }
    val listState = rememberLazyListState()

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { onImportAttachment(uri) }
                    .onSuccess {
                        attachments += it
                        attachmentError = null
                    }
                    .onFailure { attachmentError = it.message ?: "图片导入失败" }
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { onImportAttachment(uri) }
                    .onSuccess {
                        attachments += it
                        attachmentError = null
                    }
                    .onFailure { attachmentError = it.message ?: "文件导入失败" }
            }
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().background(colors.bgBase)) {
        Column(
            Modifier.fillMaxWidth().background(colors.bgLayer1)
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DsButton("菜单", onOpenMenu, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("本机 Harness", style = DsType.base16Strong, color = colors.labelPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StateDot(if (state.running) StateDotState.Running else StateDotState.Done)
                        Text(
                            if (state.running) " 手机正在执行" else " 本机模式",
                            style = DsType.caption11,
                            color = colors.labelTertiary,
                        )
                    }
                }
                DsButton("配置", onConfigure, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.model, style = DsType.xsmall12, color = colors.labelTertiary)
                Spacer(Modifier.weight(1f))
                DsButton(
                    if (state.planMode) "退出规划" else "规划模式",
                    { onPlanModeChange(!state.planMode) },
                    variant = if (state.planMode) DsButtonVariant.Info else DsButtonVariant.Outline,
                    size = DsButtonSize.Small,
                    enabled = !state.running,
                )
                DsButton(
                    "会话",
                    { showSessions = true },
                    variant = DsButtonVariant.Outline,
                    size = DsButtonSize.Small,
                    enabled = !state.running,
                )
                DsButton("新会话", onNewSession, variant = DsButtonVariant.Outline, size = DsButtonSize.Small)
            }
        }

        if (state.plan.isNotEmpty()) {
            DsCard(Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small)) {
                Text("执行计划", style = DsType.small13Strong, color = colors.labelPrimary)
                state.plan.forEachIndexed { index, item ->
                    Text("${index + 1}. $item", style = DsType.small13, color = colors.labelSecondary)
                }
            }
        }

        state.goal?.let { goal ->
            DsCard(Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small)) {
                Text("当前目标 · ${goal.status}", style = DsType.small13Strong, color = colors.labelPrimary)
                Text(goal.description, style = DsType.small13, color = colors.labelSecondary)
                goal.note?.let { Text(it, style = DsType.caption11, color = colors.labelTertiary) }
            }
        }

        if (state.todos.isNotEmpty()) {
            DsCard(Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small)) {
                Text("任务清单", style = DsType.small13Strong, color = colors.labelPrimary)
                state.todos.forEach { item ->
                    val mark = when (item.status) {
                        "completed" -> "✓"
                        "in_progress" -> "●"
                        else -> "○"
                    }
                    Text("$mark ${item.content}", style = DsType.small13, color = colors.labelSecondary)
                }
            }
        }

        if (state.jobs.isNotEmpty()) {
            Text(
                "后台任务：" + state.jobs.joinToString { "${it.id}[${it.status}]" },
                style = DsType.caption11,
                color = colors.labelTertiary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            if (state.messages.isEmpty()) {
                item { EmptyLocalHarness(state.workspacePath) }
            }
            items(state.messages, key = { it.id }) { message ->
                LocalMessageRow(message)
            }
            if (state.running) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(" 代理循环正在运行…", style = DsType.small13, color = colors.labelTertiary)
                    }
                }
            }
        }

        state.error?.let {
            Text(
                it,
                style = DsType.small13,
                color = colors.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium),
            )
        }
        attachmentError?.let {
            Text(
                it,
                style = DsType.small13,
                color = colors.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium),
            )
        }

        Column(
            Modifier.fillMaxWidth().background(colors.bgLayer1).padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            if (attachments.isNotEmpty()) {
                attachments.forEachIndexed { index, attachment ->
                    ImportedAttachmentRow(
                        attachment = attachment,
                        onRemove = { attachments.removeAt(index) },
                    )
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("交给手机上的 Harness…") },
                minLines = 1,
                maxLines = 5,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DsButton(
                    "图片",
                    { imagePicker.launch(arrayOf("image/*")) },
                    variant = DsButtonVariant.Outline,
                    size = DsButtonSize.Small,
                    enabled = !state.running,
                )
                DsButton(
                    "文件",
                    { filePicker.launch(arrayOf("*/*")) },
                    variant = DsButtonVariant.Outline,
                    size = DsButtonSize.Small,
                    enabled = !state.running,
                )
                Spacer(Modifier.weight(1f))
                if (state.running) {
                    DsButton("停止", onStop, variant = DsButtonVariant.Danger)
                } else {
                    DsButton(
                        "发送",
                        onClick = {
                            val text = input
                            val selected = attachments.toList()
                            input = ""
                            attachments.clear()
                            onSend(text, selected)
                        },
                        enabled = input.isNotBlank() || attachments.isNotEmpty(),
                    )
                }
            }
        }
    }

    state.pendingApproval?.let { ApprovalDialog(it, onApprove, onDeny) }
    state.pendingQuestion?.let { QuestionDialog(it.question, it.options, onAnswerQuestion) }
    if (showSessions) {
        DsDialog(title = "本机会话", onDismiss = { showSessions = false }) {
            if (state.sessions.isEmpty()) {
                Text("暂无已保存会话", style = DsType.small13, color = colors.labelSecondary)
            }
            state.sessions.take(12).forEach { session ->
                DsButton(
                    text = if (session.id == state.sessionId) "● ${session.title}" else session.title,
                    onClick = {
                        onSwitchSession(session.id)
                        showSessions = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = if (session.id == state.sessionId) DsButtonVariant.Info else DsButtonVariant.Outline,
                )
            }
        }
    }
}

@Composable
private fun ImportedAttachmentRow(
    attachment: LocalImportedAttachment,
    onRemove: () -> Unit,
) {
    val colors = DsTheme.colors
    DsCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(attachment.name, style = DsType.small13Strong, color = colors.labelPrimary)
                Text(
                    "${attachment.relativePath} · ${attachment.bytes} B",
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
            DsButton("移除", onRemove, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
        }
    }
}

@Composable
private fun EmptyLocalHarness(workspacePath: String) {
    val colors = DsTheme.colors
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Text("手机就是 Harness 主机", style = DsType.large20, color = colors.labelPrimary)
        Text(
            "直接聊天、处理工作区文件、搜索网页、执行安卓命令、拆分子任务。需要电脑时，从左侧菜单切换局域网或中继。",
            style = DsType.std14,
            color = colors.labelSecondary,
        )
        Text(
            workspacePath,
            style = DsType.caption11.copy(fontFamily = FontFamily.Monospace),
            color = colors.labelTertiary,
        )
    }
}

@Composable
private fun LocalMessageRow(message: LocalHarnessMessage) {
    val colors = DsTheme.colors
    val clipboard = LocalClipboardManager.current
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val isReasoning = message.role == "reasoning"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.96f),
            shape = DsShapes.block,
            color = when {
                isUser -> colors.brandPrimary.copy(alpha = 0.14f)
                isTool -> colors.bgLayer2
                isReasoning -> colors.hover
                message.role == "system" -> colors.warnTertiary
                else -> colors.bgLayer1
            },
        ) {
            Column(Modifier.padding(DsSpacing.medium), verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            isUser -> "你"
                            isTool -> "工具 · ${message.toolName}"
                            isReasoning -> "推理"
                            message.role == "system" -> "系统"
                            else -> "Harness"
                        },
                        style = DsType.caption11Strong,
                        color = colors.labelTertiary,
                    )
                    DsButton(
                        "复制",
                        { clipboard.setText(AnnotatedString(message.content)) },
                        variant = DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                }
                SelectionContainer {
                    Text(
                        message.content,
                        style = if (isTool) DsType.mdCode else DsType.bubbleText,
                        color = colors.labelPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkDiagnosticDialog(
    onDismiss: () -> Unit,
    diagnose: suspend (String) -> String,
) {
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()
    var target by rememberSaveable { mutableStateOf("https://github.com") }
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    DsDialog(title = "网络诊断", onDismiss = onDismiss) {
        Text(
            "检查实际解析地址、系统代理、VPN/TUN，以及是否被安全策略主动拦截。",
            style = DsType.small13,
            color = colors.labelSecondary,
        )
        OutlinedTextField(
            value = target,
            onValueChange = { target = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("网址或域名") },
            singleLine = true,
        )
        DsButton(
            if (running) "诊断中…" else "开始诊断",
            onClick = {
                running = true
                scope.launch {
                    result = runCatching { diagnose(target) }.getOrElse { it.message ?: "诊断失败" }
                    running = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = target.isNotBlank() && !running,
        )
        result?.let {
            SelectionContainer {
                Text(
                    it,
                    style = DsType.mdCode,
                    color = colors.labelPrimary,
                    modifier = Modifier.fillMaxWidth().height(220.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun EnvironmentInfoDialog(
    text: String,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    DsDialog(title = "环境能力", onDismiss = onDismiss) {
        Text(
            "这里展示手机本机 Harness 当前可依赖的系统能力和沙箱限制。",
            style = DsType.small13,
            color = colors.labelSecondary,
        )
        SelectionContainer {
            Text(
                text,
                style = DsType.mdCode,
                color = colors.labelPrimary,
                modifier = Modifier.fillMaxWidth().height(260.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun ApprovalDialog(approval: LocalApproval, onApprove: () -> Unit, onDeny: () -> Unit) {
    val colors = DsTheme.colors
    DsDialog(title = "需要你的批准", onDismiss = onDeny) {
        Text(approval.summary, style = DsType.base16Strong, color = colors.labelPrimary)
        SelectionContainer {
            Text(
                approval.arguments,
                style = DsType.mdCode,
                color = colors.labelSecondary,
                modifier = Modifier.fillMaxWidth().height(160.dp).verticalScroll(rememberScrollState()),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton("允许", onApprove)
            DsButton("拒绝", onDeny, variant = DsButtonVariant.Outline)
        }
    }
}

@Composable
private fun QuestionDialog(question: String, options: List<String>, onAnswer: (String) -> Unit) {
    val colors = DsTheme.colors
    var answer by rememberSaveable(question) { mutableStateOf("") }
    DsDialog(title = "Harness 需要你的决定", onDismiss = { onAnswer("用户取消了问题") }) {
        Text(question, style = DsType.base16Strong, color = colors.labelPrimary)
        options.forEach { option ->
            DsButton(
                text = option,
                onClick = { onAnswer(option) },
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Outline,
            )
        }
        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("自定义回答") },
            maxLines = 4,
        )
        DsButton(
            text = "提交回答",
            onClick = { onAnswer(answer) },
            modifier = Modifier.fillMaxWidth(),
            enabled = answer.isNotBlank(),
        )
    }
}
