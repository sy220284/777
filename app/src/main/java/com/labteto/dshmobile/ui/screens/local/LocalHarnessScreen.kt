package com.labteto.dshmobile.ui.screens.local

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalApproval
import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsCategoryRow
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsQuickActionTile
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.UserBubble
import com.labteto.dshmobile.ui.components.WhaleMark
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Default Android 16 home: local Harness first, remote transports live in the left drawer. */
@Composable
fun LocalHarnessScreen(
    onOpenRemote: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenTools: () -> Unit,
    viewModel: LocalHarnessViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var editingConfig by rememberSaveable { mutableStateOf(false) }
    var showNewSessionMode by rememberSaveable { mutableStateOf(false) }
    var filesMode by remember { mutableStateOf<LocalFilesMode?>(null) }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = state.pendingApproval != null) { viewModel.deny() }
    BackHandler(enabled = state.pendingQuestion != null) { viewModel.answerQuestion("用户取消了问题") }
    BackHandler(enabled = editingConfig) { editingConfig = false }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            LocalModeDrawer(
                currentSessionId = state.sessionId,
                sessions = state.sessions,
                onLocal = { scope.launch { drawerState.close() } },
                onRemote = {
                    scope.launch { drawerState.close() }
                    onOpenRemote()
                },
                onSwitchSession = { sessionId ->
                    viewModel.switchSession(sessionId)
                    scope.launch { drawerState.close() }
                },
                onWorkspaceFiles = {
                    filesMode = LocalFilesMode.WORKSPACE
                    scope.launch { drawerState.close() }
                },
                onTasks = {
                    scope.launch { drawerState.close() }
                    onOpenTasks()
                },
                onTools = {
                    scope.launch { drawerState.close() }
                    onOpenTools()
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
            editingConfig -> LocalConfiguration(
                state = state,
                canCancel = true,
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
                onOpenFiles = { filesMode = LocalFilesMode.CONVERSATION },
                onConfigure = { editingConfig = true },
                onSend = viewModel::send,
                onImportAttachment = viewModel::importAttachment,
                onStop = viewModel::stop,
                onNewSession = { showNewSessionMode = true },
                onPlanModeChange = viewModel::setPlanMode,
                onApprove = viewModel::approve,
                onDeny = viewModel::deny,
                onAutoApprove = viewModel::enableAutoApproval,
                onApproveDeviceTurn = viewModel::enableDeviceApprovalLease,
                onDisableDeviceTurn = viewModel::disableDeviceApprovalLease,
                onDisableAutoApprove = viewModel::disableAutoApproval,
                onAnswerQuestion = viewModel::answerQuestion,
            )
        }
    }

    if (showNewSessionMode) {
        NewSessionModeDialog(
            onDismiss = { showNewSessionMode = false },
            onSelect = { mode ->
                showNewSessionMode = false
                viewModel.createSession(mode)
            },
        )
    }

    filesMode?.let { mode ->
        LocalWorkspaceFilesDialog(
            mode = mode,
            sessionId = state.sessionId,
            loadWorkspace = viewModel::workspaceFiles,
            loadConversation = viewModel::conversationFiles,
            loadPreview = viewModel::previewWorkspaceFile,
            onDismiss = { filesMode = null },
        )
    }

}

@Composable
private fun LocalModeDrawer(
    currentSessionId: String,
    sessions: List<LocalSessionSummary>,
    onLocal: () -> Unit,
    onRemote: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onWorkspaceFiles: () -> Unit,
    onTasks: () -> Unit,
    onTools: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = DsTheme.colors
    var historyQuery by rememberSaveable { mutableStateOf("") }
    ModalDrawerSheet(
        drawerContainerColor = colors.sidebar,
        modifier = Modifier.safeDrawingPadding(),
    ) {
        Column(
            Modifier
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.large)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WhaleMark(Modifier.size(44.dp))
                Spacer(Modifier.size(DsSpacing.medium))
                Column(Modifier.weight(1f)) {
                    Text("DSH Mobile", style = DsType.large20, color = colors.labelPrimary)
                    Text("运行中心", style = DsType.caption11, color = colors.labelTertiary)
                }
                DsIconButton(
                    icon = Icons.Outlined.QrCodeScanner,
                    contentDescription = "远程控制",
                    onClick = onRemote,
                    tint = colors.labelPrimary,
                    containerColor = colors.bgLayer1,
                    shadowElevation = 2.dp,
                )
            }

            Text("运行方式", style = DsType.std14, color = colors.labelTertiary)
            DsGroupCard {
                DsCategoryRow(
                    icon = Icons.Outlined.PhoneAndroid,
                    title = "本机 Harness",
                    subtitle = "完整能力在手机内运行",
                    value = "当前",
                    onClick = onLocal,
                )
            }

            val current = sessions.firstOrNull { it.id == currentSessionId && !it.blank }
            if (current != null) {
                Text("当前会话", style = DsType.std14, color = colors.labelTertiary)
                DsGroupCard {
                    DsCategoryRow(
                        icon = Icons.Outlined.History,
                        title = current.title,
                        subtitle = "正在进行",
                        value = "当前",
                        onClick = { onSwitchSession(current.id) },
                    )
                }
            }

            val history = sessions
                .filter { it.id != currentSessionId && !it.blank }
                .sortedByDescending(LocalSessionSummary::updatedAt)
            if (history.isNotEmpty()) {
                Text("历史会话", style = DsType.std14, color = colors.labelTertiary)
                OutlinedTextField(
                    value = historyQuery,
                    onValueChange = { historyQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索会话") },
                    singleLine = true,
                    shape = DsShapes.block,
                )
                val filteredHistory = history.filter {
                    historyQuery.isBlank() || it.title.contains(historyQuery.trim(), ignoreCase = true)
                }
                if (filteredHistory.isEmpty()) {
                    Text(
                        "没有匹配的会话",
                        style = DsType.small13,
                        color = colors.labelTertiary,
                        modifier = Modifier.padding(horizontal = DsSpacing.small),
                    )
                } else {
                    DsGroupCard {
                        filteredHistory.forEach { session ->
                            DsCategoryRow(
                                icon = Icons.Outlined.History,
                                title = session.title,
                                subtitle = "本机会话",
                                onClick = { onSwitchSession(session.id) },
                            )
                        }
                    }
                }
            }

            Text("功能", style = DsType.std14, color = colors.labelTertiary)
            DsGroupCard {
                DsCategoryRow(
                    icon = FeatherIcons.FileText,
                    title = stringResource(R.string.chatlist_workspace_files),
                    subtitle = stringResource(R.string.local_files_workspace_subtitle),
                    onClick = onWorkspaceFiles,
                )
                DsCategoryRow(
                    icon = Icons.Outlined.Schedule,
                    title = "任务",
                    subtitle = "计划任务、周期任务和执行结果",
                    onClick = onTasks,
                )
                DsCategoryRow(
                    icon = Icons.Outlined.Extension,
                    title = "工具与连接",
                    subtitle = "外部工具服务与当前扩展",
                    onClick = onTools,
                )
                DsCategoryRow(
                    icon = Icons.Outlined.Settings,
                    title = "设置",
                    subtitle = "模型、个性化、权限与高级选项",
                    onClick = onSettings,
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        Modifier.fillMaxSize().background(DsTheme.colors.bgBase),
        contentAlignment = Alignment.Center,
    ) {
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
        Modifier.fillMaxSize().background(colors.bgBase).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState())
            .padding(DsSpacing.xlarge),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DsIconButton(
                icon = FeatherIcons.Menu,
                contentDescription = "菜单",
                onClick = onOpenMenu,
                containerColor = colors.bgLayer1,
                shadowElevation = 3.dp,
            )
            Text(
                "本机 Harness",
                style = DsType.large20,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            if (canCancel) {
                DsButton("取消", onCancel, variant = DsButtonVariant.Ghost)
            } else {
                Spacer(Modifier.size(56.dp))
            }
        }

        DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Text("手机直接执行", style = DsType.base16Strong, color = colors.labelPrimary)
            Text(
                "模型、文件、网页、命令、技能和子代理都在手机侧组织执行。远程控制入口已统一放到侧边栏。",
                style = DsType.std14,
                color = colors.labelSecondary,
            )
        }

        Text("模型与接口", style = DsType.std14, color = colors.labelTertiary)
        DsGroupCard {
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
            Spacer(Modifier.height(DsSpacing.medium))
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Text("模型", style = DsType.std14Strong, color = colors.labelPrimary)
                ModelChoice("deepseek-chat", "DeepSeek Chat｜工具执行", model) { model = it }
                ModelChoice("deepseek-reasoner", "DeepSeek Reasoner｜深度推理", model) { model = it }
            }
            Spacer(Modifier.height(DsSpacing.medium))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("接口地址") },
                supportingText = { Text("兼容 OpenAI 聊天补全协议的服务也可使用。") },
                singleLine = true,
            )
        }

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
    onOpenFiles: () -> Unit,
    onConfigure: () -> Unit,
    onSend: (String, List<LocalImportedAttachment>) -> Unit,
    onImportAttachment: suspend (android.net.Uri) -> LocalImportedAttachment,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    onPlanModeChange: (Boolean) -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAutoApprove: () -> Unit,
    onApproveDeviceTurn: () -> Unit,
    onDisableDeviceTurn: () -> Unit,
    onDisableAutoApprove: () -> Unit,
    onAnswerQuestion: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()
    val drafts = rememberSaveable(
        saver = listSaver(
            save = { map -> map.entries.flatMap { listOf(it.key, it.value) } },
            restore = { values ->
                mutableStateMapOf<String, String>().apply {
                    values.chunked(2).forEach { pair ->
                        if (pair.size == 2) this[pair[0]] = pair[1]
                    }
                }
            },
        ),
    ) { mutableStateMapOf<String, String>() }
    val input = drafts[state.sessionId].orEmpty()
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var showAttachmentPicker by rememberSaveable { mutableStateOf(false) }
    var scrollShortcut by remember { mutableStateOf<String?>(null) }
    val attachments = remember { mutableStateListOf<LocalImportedAttachment>() }
    val listState = rememberLazyListState()
    val transcriptItems = remember(state.messages) { buildLocalTranscript(state.messages) }

    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val movingTowardBottom = index > previousIndex || (index == previousIndex && offset > previousOffset)
                val movingTowardTop = index < previousIndex || (index == previousIndex && offset < previousOffset)
                if (movingTowardBottom && listState.canScrollBackward) scrollShortcut = "top"
                if (movingTowardTop && listState.canScrollForward) scrollShortcut = "bottom"
                if (!listState.canScrollBackward && scrollShortcut == "top") scrollShortcut = null
                if (!listState.canScrollForward && scrollShortcut == "bottom") scrollShortcut = null
                previousIndex = index
                previousOffset = offset
            }
    }

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

    LaunchedEffect(state.sessionId) {
        attachments.clear()
        attachmentError = null
        if (transcriptItems.isNotEmpty()) {
            listState.scrollToItem(transcriptItems.lastIndex)
        }
    }

    LaunchedEffect(state.messages.size, transcriptItems.size) {
        if (transcriptItems.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= transcriptItems.lastIndex - 2) {
                listState.animateScrollToItem(transcriptItems.lastIndex)
            }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().background(colors.bgBase)) {
        Column(
            Modifier.fillMaxWidth().background(colors.bgBase)
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DsIconButton(
                    icon = FeatherIcons.Menu,
                    contentDescription = "菜单",
                    onClick = onOpenMenu,
                    tint = colors.labelPrimary,
                    containerColor = colors.bgLayer1,
                    shadowElevation = 3.dp,
                )
                Spacer(Modifier.width(DsSpacing.small))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = colors.bgLayer1,
                    shadowElevation = 1.dp,
                    modifier = Modifier.weight(1f, fill = false)
                        .clickable(onClickLabel = "配置本机 Harness", onClick = onConfigure),
                ) {
                    Row(
                        Modifier.padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StateDot(if (state.running) StateDotState.Running else StateDotState.Done)
                        Spacer(Modifier.width(DsSpacing.small))
                        Column {
                            Text("本机 Harness", style = DsType.std14Strong, color = colors.labelPrimary)
                            Text(
                                when {
                                    !state.configured -> "未配置 · 点此设置"
                                    state.pendingApproval != null -> "等待批准"
                                    state.pendingQuestion != null -> "等待回答"
                                    state.running -> state.model.removePrefix("deepseek-") + " · 执行中"
                                    else -> state.model.removePrefix("deepseek-") + " · 已就绪"
                                },
                                style = DsType.small13,
                                color = colors.labelTertiary,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                DsIconButton(
                    icon = FeatherIcons.FileText,
                    contentDescription = stringResource(R.string.chat_open_files),
                    onClick = onOpenFiles,
                    tint = colors.labelPrimary,
                    containerColor = colors.bgLayer1,
                    shadowElevation = 3.dp,
                )
                Spacer(Modifier.width(DsSpacing.small))
                DsIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "新建会话",
                    onClick = onNewSession,
                    tint = colors.labelPrimary,
                    containerColor = colors.bgLayer1,
                    shadowElevation = 3.dp,
                )
            }
        }

        if (state.safeAutoApprovalEnabled || state.deviceApprovalLease) {
            Surface(
                color = colors.warnTertiary,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.tiny),
            ) {
                Row(
                    Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (state.deviceApprovalLease) "本轮设备操作已授权" else "安全操作自动批准已开启",
                            style = DsType.small13Strong,
                            color = colors.warnLabel,
                        )
                        Text(
                            if (state.deviceApprovalLease) {
                                "当前代理回合中的普通界面点击、输入和滑动会直接执行；Shizuku、高权限、MCP、凭据和命令仍需逐次确认。"
                            } else {
                                "工作区内受边界保护的写入、编辑、补丁和下载，以及工作区外纯只读操作会直接执行；该设置跨对话保持。命令、外部写入、联网写入、设备及其他高风险操作仍按影响等级确认。"
                            },
                            style = DsType.caption11,
                            color = colors.labelSecondary,
                        )
                    }
                    DsButton(
                        "关闭",
                        if (state.deviceApprovalLease) onDisableDeviceTurn else onDisableAutoApprove,
                        variant = DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                }
            }
        }

        if (state.plan.isNotEmpty() || state.goal != null || state.todos.isNotEmpty() || state.jobs.isNotEmpty()) {
            ExecutionStatusCard(
                state = state,
                modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.tiny),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = DsSpacing.medium,
                    end = DsSpacing.medium,
                    top = DsSpacing.comfortable,
                    bottom = DsSpacing.xlarge,
                ),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
            ) {
                if (transcriptItems.isEmpty()) {
                    item {
                        EmptyLocalHarness { suggestion ->
                            drafts[state.sessionId] = suggestion
                        }
                    }
                }
                items(transcriptItems, key = { it.key }) { transcriptItem ->
                    when (transcriptItem) {
                        is LocalTranscriptItem.Message -> LocalMessageRow(transcriptItem.message)
                        is LocalTranscriptItem.WorkProcess -> WorkProcessRow(transcriptItem.messages)
                    }
                }
                if (state.running) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(" 代理正在处理…", style = DsType.small13, color = colors.labelTertiary)
                        }
                    }
                }
            }

            val shortcut = scrollShortcut
            if (shortcut == "top" && listState.canScrollBackward) {
                ScrollShortcut(
                    text = "↑",
                    description = "回到顶部",
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(end = DsSpacing.medium, bottom = DsSpacing.small),
                ) {
                    scope.launch {
                        listState.animateScrollToItem(0)
                        scrollShortcut = null
                    }
                }
            } else if (shortcut == "bottom" && listState.canScrollForward) {
                ScrollShortcut(
                    text = "↓",
                    description = "直达底部",
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(end = DsSpacing.medium, bottom = DsSpacing.small),
                ) {
                    scope.launch {
                        val target = transcriptItems.lastIndex.coerceAtLeast(0)
                        listState.animateScrollToItem(target)
                        scrollShortcut = null
                    }
                }
            }
        }

        state.error?.let { error ->
            Surface(
                color = colors.warnTertiary,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium),
            ) {
                Row(
                    Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Text(
                        error,
                        style = DsType.small13,
                        color = colors.error,
                        modifier = Modifier.weight(1f),
                    )
                    state.messages.lastOrNull { message -> message.role == "user" }?.let { lastRequest ->
                        DsButton(
                            "恢复请求",
                            { drafts[state.sessionId] = lastRequest.content },
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                    }
                }
            }
        }
        attachmentError?.let {
            Text(
                it,
                style = DsType.small13,
                color = colors.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium),
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            shape = DsShapes.composer,
            color = colors.composerCard,
            shadowElevation = 1.dp,
        ) {
            Column(
                Modifier.padding(DsSpacing.medium),
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
                    onValueChange = { drafts[state.sessionId] = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("问点什么，或直接交给 Harness 执行…") },
                    shape = DsShapes.block,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                    ),
                    minLines = 1,
                    maxLines = 5,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DsIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = "添加附件",
                        onClick = { showAttachmentPicker = true },
                        enabled = !state.running,
                        tint = colors.labelPrimary,
                        containerColor = colors.bgModulePlatform,
                    )
                    DsButton(
                        if (state.planMode) "规划中" else "规划",
                        { onPlanModeChange(!state.planMode) },
                        variant = if (state.planMode) DsButtonVariant.Info else DsButtonVariant.Ghost,
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
                                if (!state.configured) {
                                    onConfigure()
                                } else {
                                    val selected = attachments.toList()
                                    onSend(input, selected)
                                    drafts[state.sessionId] = ""
                                    attachments.clear()
                                }
                            },
                            enabled = input.isNotBlank() || attachments.isNotEmpty(),
                        )
                    }
                }
            }
        }
    }

    state.pendingApproval?.let {
        ApprovalDialog(
            approval = it,
            safeAutoApprovalEnabled = state.safeAutoApprovalEnabled,
            onApprove = onApprove,
            onDeny = onDeny,
            onAutoApprove = onAutoApprove,
            onApproveDeviceTurn = onApproveDeviceTurn,
        )
    }
    state.pendingQuestion?.let { QuestionDialog(it.question, it.options, onAnswerQuestion) }
    if (showAttachmentPicker) {
        DsBottomSheet(title = "添加附件", onDismiss = { showAttachmentPicker = false }) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                DsQuickActionTile(
                    icon = Icons.Outlined.Image,
                    label = "图片",
                    onClick = {
                        showAttachmentPicker = false
                        imagePicker.launch(arrayOf("image/*"))
                    },
                    modifier = Modifier.weight(1f),
                )
                DsQuickActionTile(
                    icon = Icons.Outlined.AttachFile,
                    label = "文件",
                    onClick = {
                        showAttachmentPicker = false
                        filePicker.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.weight(1f),
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
                    "${attachment.mediaType} · ${attachment.bytes} B",
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
            DsButton("移除", onRemove, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
        }
    }
}

@Composable
private fun EmptyLocalHarness(onSuggestion: (String) -> Unit) {
    val colors = DsTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DsSpacing.large, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        WhaleMark(Modifier.size(72.dp).shadow(14.dp, CircleShape, clip = false))
        Spacer(Modifier.height(DsSpacing.medium))
        Text("今天想让手机做点什么？", style = DsType.display24, color = colors.labelPrimary)
        Text(
            "可以直接聊天，也可以让我处理文件、联网查资料、执行命令或拆分复杂任务。",
            style = DsType.std14,
            color = colors.labelSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(DsSpacing.small))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            DsQuickActionTile(
                icon = FeatherIcons.FileText,
                label = "整理文件",
                onClick = { onSuggestion("帮我整理工作区文件，并先给出安全的执行计划") },
                modifier = Modifier.weight(1f),
            )
            DsQuickActionTile(
                icon = FeatherIcons.Globe,
                label = "联网调研",
                onClick = { onSuggestion("联网调研这个主题，列出来源、结论和待核实事项：") },
                modifier = Modifier.weight(1f),
            )
            DsQuickActionTile(
                icon = FeatherIcons.CheckSquare,
                label = "拆解任务",
                onClick = { onSuggestion("把这项任务拆成可执行步骤，并从第一步开始：") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ExecutionStatusCard(
    state: LocalHarnessState,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    var expanded by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    val completed = state.todos.count { it.status == "completed" }
    val total = state.todos.size
    val summary = buildList {
        state.goal?.let { add("目标 ${it.status}") }
        if (state.plan.isNotEmpty()) add("计划 ${state.plan.size} 步")
        if (total > 0) add("任务 $completed/$total")
        if (state.jobs.isNotEmpty()) add("后台 ${state.jobs.size}")
    }.joinToString(" · ")

    Surface(
        modifier = modifier.fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "收起执行状态" else "展开执行状态") {
                expanded = !expanded
            },
        shape = RoundedCornerShape(12.dp),
        color = colors.bgModulePlatform,
    ) {
        Column(
            Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (expanded) "⌄" else "›",
                    style = DsType.base16Strong,
                    color = colors.labelTertiary,
                )
                Spacer(Modifier.size(DsSpacing.small))
                Column(Modifier.weight(1f)) {
                    Text("执行状态", style = DsType.small13Strong, color = colors.labelPrimary)
                    Text(summary, style = DsType.caption11, color = colors.labelTertiary)
                }
            }
            if (expanded) {
                state.goal?.let { goal ->
                    Text("目标 · ${goal.status}", style = DsType.caption11Strong, color = colors.labelTertiary)
                    Text(goal.description, style = DsType.small13, color = colors.labelSecondary)
                    goal.note?.let { Text(it, style = DsType.caption11, color = colors.labelTertiary) }
                }
                if (state.plan.isNotEmpty()) {
                    Text("计划", style = DsType.caption11Strong, color = colors.labelTertiary)
                    state.plan.forEachIndexed { index, item ->
                        Text("${index + 1}. $item", style = DsType.small13, color = colors.labelSecondary)
                    }
                }
                if (state.todos.isNotEmpty()) {
                    Text("任务", style = DsType.caption11Strong, color = colors.labelTertiary)
                    state.todos.forEach { item ->
                        val mark = when (item.status) {
                            "completed" -> "✓"
                            "in_progress" -> "●"
                            else -> "○"
                        }
                        Text("$mark ${item.content}", style = DsType.small13, color = colors.labelSecondary)
                    }
                }
                if (state.jobs.isNotEmpty()) {
                    Text(
                        "后台 · " + state.jobs.joinToString { "${it.label}[${it.status}]" },
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalMessageRow(message: LocalHarnessMessage) {
    val colors = DsTheme.colors
    val clipboard = LocalClipboardManager.current

    when (message.role) {
        "user" -> UserBubble(message.content)

        "system" -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = colors.warnTertiary,
        ) {
            Text(
                message.content,
                style = DsType.small13,
                color = colors.labelSecondary,
                modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            )
        }

        "reasoning", "tool", "progress" -> WorkProcessRow(listOf(message))

        else -> Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            MarkdownText(message.content)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DsButton(
                    "复制答复",
                    { clipboard.setText(AnnotatedString(message.content)) },
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun WorkProcessRow(messages: List<LocalHarnessMessage>) {
    if (messages.isEmpty()) return

    val colors = DsTheme.colors
    var expanded by rememberSaveable(messages.first().id) { mutableStateOf(false) }
    val toolCount = messages.count { it.role == "tool" }
    val summary = buildList {
        add("已折叠 ${messages.size} 条过程")
        if (toolCount > 0) add("${toolCount} 次工具调用")
    }.joinToString(" · ")

    Surface(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "收起工作过程" else "展开工作过程") {
                expanded = !expanded
            },
        shape = RoundedCornerShape(12.dp),
        color = colors.bgModulePlatform,
    ) {
        Column(
            Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Text(
                    if (expanded) "⌄" else "›",
                    style = DsType.base16Strong,
                    color = colors.labelTertiary,
                )
                Column(Modifier.weight(1f)) {
                    Text("工作过程", style = DsType.small13Strong, color = colors.labelSecondary)
                    Text(summary, style = DsType.caption11, color = colors.labelTertiary)
                }
            }

            if (expanded) {
                messages.forEach { message ->
                    val title = when (message.role) {
                        "reasoning" -> "思考"
                        "tool" -> "工具 · ${message.toolName ?: "执行结果"}"
                        else -> "执行说明"
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                        Text(title, style = DsType.caption11Strong, color = colors.labelTertiary)
                        if (message.role == "tool") {
                            Text(
                                toolResultMeta(message.content),
                                style = DsType.caption11,
                                color = colors.labelTertiary,
                            )
                            SelectionContainer {
                                Text(
                                    message.content,
                                    style = DsType.mdCode,
                                    color = colors.labelSecondary,
                                )
                            }
                        } else {
                            MarkdownText(message.content, allowCodeCopy = false)
                        }
                    }
                }
            }
        }
    }
}

private fun toolResultMeta(content: String): String = when {
    "工具执行失败" in content || "[TOOL_TIMEOUT]" in content || "[MODEL_TIMEOUT]" in content ||
        "[NETWORK_ERROR]" in content || "[DNS_FAILED]" in content || "[SSRF_BLOCKED]" in content ->
        "执行失败"
    "已自动降级" in content || ("达到" in content && "步上限" in content) ->
        "已降级/未完整结束"
    else -> "已完成"
}

@Composable
private fun ScrollShortcut(
    text: String,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = modifier.size(42.dp)
            .clickable(onClickLabel = description, onClick = onClick),
        shape = CircleShape,
        color = colors.bgLayer2,
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = DsType.large20, color = colors.labelPrimary)
        }
    }
}

@Composable
private fun NewSessionModeDialog(
    onDismiss: () -> Unit,
    onSelect: (LocalConversationMode) -> Unit,
) {
    val colors = DsTheme.colors
    DsDialog(title = "新建对话", onDismiss = onDismiss) {
        Text(
            "选择新对话可以使用哪些已有上下文。长期规则始终保留，其他内容按作用域隔离。",
            style = DsType.small13,
            color = colors.labelSecondary,
        )
        DsButton(
            text = "继续当前任务",
            onClick = { onSelect(LocalConversationMode.CONTINUATION) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "继承当前项目、对话链记忆和精简交接摘要，不复制整段旧聊天。",
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        DsButton(
            text = "同项目新对话",
            onClick = { onSelect(LocalConversationMode.PROJECT) },
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Outline,
        )
        Text(
            "保留全局和当前项目记忆，不带上一条对话的临时任务状态。",
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        DsButton(
            text = "独立新对话",
            onClick = { onSelect(LocalConversationMode.INDEPENDENT) },
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Ghost,
        )
        Text(
            "只使用全局规则和全局长期记忆。",
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
    }
}

@Composable
internal fun NetworkDiagnosticDialog(
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
            "检查实际解析地址、系统代理、VPN/TUN、安全策略，并发起受限 HTTP/TLS 探测验证真实连通性。",
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
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
internal fun EnvironmentInfoDialog(
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
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun ApprovalDialog(
    approval: LocalApproval,
    safeAutoApprovalEnabled: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAutoApprove: () -> Unit,
    onApproveDeviceTurn: () -> Unit,
) {
    val colors = DsTheme.colors
    DsDialog(title = "执行前确认", onDismiss = onDeny) {
        Text(approval.summary, style = DsType.base16Strong, color = colors.labelPrimary)
        Text(
            "影响等级：${approvalImpactLabel(approval.impact)}",
            style = DsType.caption11Strong,
            color = when (approval.impact) {
                com.labteto.dshmobile.local.LocalApprovalImpact.LOW -> colors.labelTertiary
                com.labteto.dshmobile.local.LocalApprovalImpact.MEDIUM -> colors.warnLabel
                com.labteto.dshmobile.local.LocalApprovalImpact.HIGH,
                com.labteto.dshmobile.local.LocalApprovalImpact.CRITICAL -> colors.error
            },
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.bgModulePlatform,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                Text("这项操作是做什么的？", style = DsType.small13Strong, color = colors.labelPrimary)
                Text(
                    approvalPurpose(approval),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                )
            }
        }

        Text("具体内容", style = DsType.small13Strong, color = colors.labelPrimary)
        SelectionContainer {
            Text(
                approval.arguments,
                style = DsType.mdCode,
                color = colors.labelSecondary,
                modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()),
            )
        }

        Text(
            when {
                approval.canAutoApproveSafely ->
                    "该操作属于安全自动批准范围：受工作区边界约束的写入，或不会改变外部状态的只读操作。开启后会跨对话持续生效。"
                approval.canApproveDeviceTurn ->
                    "该操作影响设备状态，需要确认。可只批准本次，或仅在当前代理回合内授权普通设备界面操作；高权限设备能力仍逐次确认。"
                else ->
                    "该操作会影响工作区外状态、进程、网络、设备或高权限资源，需要按影响等级确认。安全自动批准不会绕过当前操作。"
            },
            style = DsType.caption11,
            color = colors.labelTertiary,
        )

        DsButton(
            "仅批准本次",
            onApprove,
            modifier = Modifier.fillMaxWidth(),
        )
        DsButton(
            "拒绝",
            onDeny,
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Outline,
        )
        if (!safeAutoApprovalEnabled) {
            DsButton(
                "开启安全操作自动批准",
                onAutoApprove,
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Ghost,
            )
        } else {
            Text(
                "安全操作自动批准已开启，并会在后续对话继续生效。",
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
        if (approval.canApproveDeviceTurn) {
            DsButton(
                "批准本轮普通设备操作",
                onApproveDeviceTurn,
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Ghost,
            )
        }
    }
}

private fun approvalImpactLabel(impact: com.labteto.dshmobile.local.LocalApprovalImpact): String = when (impact) {
    com.labteto.dshmobile.local.LocalApprovalImpact.LOW -> "低"
    com.labteto.dshmobile.local.LocalApprovalImpact.MEDIUM -> "中"
    com.labteto.dshmobile.local.LocalApprovalImpact.HIGH -> "高"
    com.labteto.dshmobile.local.LocalApprovalImpact.CRITICAL -> "关键"
}

private fun approvalPurpose(approval: LocalApproval): String = when (approval.toolName) {
    "bash", "run_shell" ->
        "Harness 准备在手机的本机执行环境中运行一条系统命令，用来完成当前任务中的检查、构建、文件处理或其他自动化步骤。命令可能读写工作区、访问网络或启动进程，具体影响取决于下方命令内容。"
    "write", "write_file" ->
        "Harness 准备创建或完整写入一个工作区文件，用来保存代码、配置、文档或任务产物。批准后会实际改变工作区内容。"
    "edit", "edit_file" ->
        "Harness 准备修改现有工作区文件，用来落实当前任务要求。批准后会对目标文件产生真实改动。"
    "lsp_definition", "lsp_references", "lsp_hover", "lsp_implementation",
    "lsp_symbols", "lsp_workspace_symbols", "lsp_rename_preview", "lsp_diagnostics" ->
        "777 准备启动与当前项目匹配的代码智能分析进程，用来理解定义、引用、类型、实现和诊断信息。它只接收当前工作区内的代码；批准后同一分析进程会在后续查询中复用，不会反复询问。"
    else ->
        "Harness 请求执行一项会改变本机状态的操作。批准后会真实执行；拒绝则跳过这一步并把结果返回给模型。"
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
