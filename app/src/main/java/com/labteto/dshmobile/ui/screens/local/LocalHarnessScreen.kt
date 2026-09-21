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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.snapshotFlow
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
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.flow.collect
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
                onAutoApprove = viewModel::enableAutoApproval,
                onDisableAutoApprove = viewModel::disableAutoApproval,
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
    onAutoApprove: () -> Unit,
    onDisableAutoApprove: () -> Unit,
    onAnswerQuestion: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var showSessions by rememberSaveable { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var scrollShortcut by remember { mutableStateOf<String?>(null) }
    val attachments = remember { mutableStateListOf<LocalImportedAttachment>() }
    val listState = rememberLazyListState()

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
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= state.messages.lastIndex - 2) {
                listState.animateScrollToItem(state.messages.lastIndex)
            }
        }
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
                            if (state.running) " 执行中" else " 已就绪",
                            style = DsType.caption11,
                            color = colors.labelTertiary,
                        )
                    }
                }
                DsButton("新建", onNewSession, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = colors.bgModulePlatform,
                ) {
                    Text(
                        state.model.removePrefix("deepseek-"),
                        style = DsType.caption11,
                        color = colors.labelSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                DsButton(
                    if (state.planMode) "规划中" else "规划",
                    { onPlanModeChange(!state.planMode) },
                    variant = if (state.planMode) DsButtonVariant.Info else DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                    enabled = !state.running,
                )
                DsButton(
                    "会话",
                    { showSessions = true },
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                    enabled = !state.running,
                )
                DsButton("配置", onConfigure, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
            }
        }

        if (state.autoApproveMutations) {
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
                        Text("自动批准已开启", style = DsType.small13Strong, color = colors.warnLabel)
                        Text(
                            "当前会话中的文件写入、编辑和命令将直接执行。",
                            style = DsType.caption11,
                            color = colors.labelSecondary,
                        )
                    }
                    DsButton(
                        "关闭",
                        onDisableAutoApprove,
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
                        val target = (state.messages.size - 1).coerceAtLeast(0)
                        listState.animateScrollToItem(target)
                        scrollShortcut = null
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
                placeholder = { Text("问点什么，或直接交给 Harness 执行…") },
                shape = RoundedCornerShape(20.dp),
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

    state.pendingApproval?.let { ApprovalDialog(it, onApprove, onDeny, onAutoApprove) }
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
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Text("今天想做点什么？", style = DsType.display24, color = colors.labelPrimary)
        Text(
            "可以直接聊天，也可以让我处理文件、联网查资料、执行命令或拆分复杂任务。",
            style = DsType.std14,
            color = colors.labelSecondary,
        )
        Text(
            workspacePath,
            style = DsType.caption11.copy(fontFamily = FontFamily.Monospace),
            color = colors.labelDimmed,
        )
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
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val isReasoning = message.role == "reasoning"

    when {
        isReasoning -> CollapsibleTranscriptRow(
            title = "思考过程",
            meta = "已思考 ${message.content.length} 字",
            content = message.content,
            code = false,
            onCopy = { clipboard.setText(AnnotatedString(message.content)) },
        )

        isTool -> CollapsibleTranscriptRow(
            title = "工具 · ${message.toolName ?: "执行结果"}",
            meta = toolResultMeta(message.content),
            content = message.content,
            code = true,
            onCopy = { clipboard.setText(AnnotatedString(message.content)) },
        )

        isUser -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.86f),
                shape = RoundedCornerShape(18.dp),
                color = colors.userBubble,
            ) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                ) {
                    SelectionContainer {
                        Text(message.content, style = DsType.bubbleText, color = colors.labelPrimary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        DsButton(
                            "复制",
                            { clipboard.setText(AnnotatedString(message.content)) },
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                    }
                }
            }
        }

        message.role == "system" -> Surface(
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

        else -> Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            SelectionContainer {
                Text(
                    message.content,
                    style = DsType.mdBody,
                    color = colors.labelPrimary,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DsButton(
                    "复制",
                    { clipboard.setText(AnnotatedString(message.content)) },
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
        }
    }
}

private fun toolResultMeta(content: String): String = when {
    "工具执行失败" in content || "[TOOL_TIMEOUT]" in content || "[MODEL_TIMEOUT]" in content ||
        "[NETWORK_ERROR]" in content || "[DNS_FAILED]" in content || "[SSRF_BLOCKED]" in content ->
        "执行失败，点击查看详情"
    "已自动降级" in content || ("达到" in content && "步上限" in content) ->
        "已降级/未完整结束，点击查看详情"
    else -> "已完成，点击查看详情"
}
@Composable
private fun CollapsibleTranscriptRow(
    title: String,
    meta: String,
    content: String,
    code: Boolean,
    onCopy: () -> Unit,
) {
    val colors = DsTheme.colors
    var expanded by rememberSaveable(title, content.hashCode()) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "收起" else "展开") { expanded = !expanded },
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
                    Text(title, style = DsType.small13Strong, color = colors.labelSecondary)
                    Text(meta, style = DsType.caption11, color = colors.labelTertiary)
                }
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        content,
                        style = if (code) DsType.mdCode else DsType.mdSmall,
                        color = colors.labelSecondary,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    DsButton(
                        "复制",
                        onCopy,
                        variant = DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                }
            }
        }
    }
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
private fun ApprovalDialog(
    approval: LocalApproval,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAutoApprove: () -> Unit,
) {
    val colors = DsTheme.colors
    DsDialog(title = "执行前确认", onDismiss = onDeny) {
        Text(approval.summary, style = DsType.base16Strong, color = colors.labelPrimary)

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
                modifier = Modifier.fillMaxWidth().height(140.dp).verticalScroll(rememberScrollState()),
            )
        }

        Text(
            "“自动批准”只对当前会话生效。开启后，后续写文件、编辑文件和执行命令将直接运行，顶部会持续显示提示，可随时关闭。",
            style = DsType.caption11,
            color = colors.labelTertiary,
        )

        DsButton(
            "批准",
            onApprove,
            modifier = Modifier.fillMaxWidth(),
        )
        DsButton(
            "自动批准",
            onAutoApprove,
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Info,
        )
        DsButton(
            "拒绝",
            onDeny,
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Outline,
        )
    }
}

private fun approvalPurpose(approval: LocalApproval): String = when (approval.toolName) {
    "bash", "run_shell" ->
        "Harness 准备在手机的本机执行环境中运行一条系统命令，用来完成当前任务中的检查、构建、文件处理或其他自动化步骤。命令可能读写工作区、访问网络或启动进程，具体影响取决于下方命令内容。"
    "write", "write_file" ->
        "Harness 准备创建或完整写入一个工作区文件，用来保存代码、配置、文档或任务产物。批准后会实际改变工作区内容。"
    "edit", "edit_file" ->
        "Harness 准备修改现有工作区文件，用来落实当前任务要求。批准后会对目标文件产生真实改动。"
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
