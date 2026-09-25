package com.labteto.dshmobile.ui.screens.local

import android.graphics.BitmapFactory
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.window.Dialog
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.DeepSeekUsageSnapshot
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalImportedAttachment
import com.labteto.dshmobile.local.LocalImageInputMode
import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.agentOperationKind
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.agentOperationStatusRes
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.AppBrandIcon
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsCategoryRow
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
import java.util.Locale
import kotlin.math.roundToInt
import com.labteto.dshmobile.ui.theme.rootSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Default Android 16 home: local Harness first, remote transports live in the left drawer. */
@Composable
fun LocalHarnessScreen(
    onOpenRemote: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenTools: () -> Unit,
    onCheckUpdate: () -> Unit,
    updateStatus: String?,
    viewModel: LocalHarnessViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showNewSessionMode by rememberSaveable { mutableStateOf(false) }
    var filesMode by remember { mutableStateOf<LocalFilesMode?>(null) }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    val pendingApprovalCallId = state.pendingApproval?.callId
    val pendingQuestionCallId = state.pendingQuestion?.callId
    BackHandler(enabled = pendingApprovalCallId != null) {
        pendingApprovalCallId?.let(viewModel::deny)
    }
    BackHandler(enabled = pendingQuestionCallId != null) {
        pendingQuestionCallId?.let(viewModel::cancelQuestion)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            LocalModeDrawer(
                currentSessionId = state.sessionId,
                sessions = state.sessions,
                usageMode = state.usageMode,
                usage = state.usage,
                onNewSession = {
                    scope.launch { drawerState.close() }
                    showNewSessionMode = true
                },
                onRemote = {
                    scope.launch { drawerState.close() }
                    onOpenRemote()
                },
                onSwitchSession = { sessionId ->
                    viewModel.switchSession(sessionId)
                    scope.launch { drawerState.close() }
                },
                onDeleteSessions = { ids -> scope.launch { viewModel.deleteSessions(ids) } },
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
                onCheckUpdate = onCheckUpdate,
                updateStatus = updateStatus,
            )
        },
    ) {
        when {
            state.loading -> LoadingScreen()
            else -> LocalChat(
                state = state,
                onOpenMenu = { scope.launch { drawerState.open() } },
                onOpenFiles = { filesMode = LocalFilesMode.CONVERSATION },
                onConfigure = onOpenSettings,
                onSelectModel = viewModel::selectModel,
                onSend = viewModel::send,
                onRegenerate = viewModel::regenerateReply,
                onImportAttachment = viewModel::importAttachment,
                onImageModeChange = viewModel::setImageInputMode,
                onStop = viewModel::stop,
                onNewSession = { showNewSessionMode = true },
                onUsageModeChange = viewModel::switchUsageMode,
                onConfigureChatPersona = viewModel::configureChatPersona,
                onAutoFillChatPersona = viewModel::autoFillChatPersona,
                onPlanModeChange = viewModel::setPlanMode,
                onApprove = viewModel::approve,
                onDeny = viewModel::deny,
                onAutoApprove = viewModel::enableAutoApproval,
                onAutoApprovePending = viewModel::enableAutoApprovalForPending,
                onApproveDeviceTurn = viewModel::enableDeviceApprovalLease,
                onDisableDeviceTurn = viewModel::disableDeviceApprovalLease,
                onDisableAutoApprove = viewModel::disableAutoApproval,
                onAnswerQuestion = viewModel::answerQuestion,
                onCancelQuestion = viewModel::cancelQuestion,
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
            workspacePath = state.workspacePath,
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
    usageMode: LocalUsageMode,
    usage: DeepSeekUsageSnapshot,
    onNewSession: () -> Unit,
    onRemote: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onDeleteSessions: (Set<String>) -> Unit,
    onWorkspaceFiles: () -> Unit,
    onTasks: () -> Unit,
    onTools: () -> Unit,
    onSettings: () -> Unit,
    onCheckUpdate: () -> Unit,
    updateStatus: String?,
) {
    val colors = DsTheme.colors
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var selectionOpen by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    val visibleSessions = remember(sessions, usageMode) {
        sessions.filter { !it.blank && it.usageMode == usageMode }
            .sortedByDescending(LocalSessionSummary::updatedAt)
    }
    val filteredSessions = remember(visibleSessions, historyQuery) {
        val query = historyQuery.trim()
        if (query.isEmpty()) visibleSessions else visibleSessions.filter {
            it.title.contains(query, ignoreCase = true)
        }
    }
    ModalDrawerSheet(
        drawerContainerColor = colors.sidebar,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(Modifier.fillMaxHeight().safeDrawingPadding()) {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.large),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                item(key = "drawer-header") {
                    Row(
                        modifier = Modifier.padding(bottom = DsSpacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppBrandIcon(Modifier.size(44.dp))
                        Spacer(Modifier.size(DsSpacing.medium))
                        Text(stringResource(R.string.app_name), style = DsType.large20, color = colors.labelPrimary, modifier = Modifier.weight(1f))
                        DsIconButton(
                            icon = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.chatlist_new_session),
                            onClick = onNewSession,
                            tint = colors.labelPrimary,
                        )
                    }
                }
                item(key = "drawer-search") {
                    OutlinedTextField(
                        value = historyQuery,
                        onValueChange = { historyQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = DsSpacing.medium),
                        placeholder = { Text("搜索会话") },
                        singleLine = true,
                        shape = DsShapes.block,
                    )
                }
                item(key = "drawer-sessions-title") {
                    DrawerSectionTitle("会话")
                }
                if (visibleSessions.isNotEmpty() && filteredSessions.isEmpty()) {
                    item(key = "drawer-no-sessions") {
                        Text(
                            "没有匹配的会话",
                            style = DsType.small13,
                            color = colors.labelTertiary,
                            modifier = Modifier.padding(horizontal = DsSpacing.small),
                        )
                    }
                }
                items(filteredSessions, key = { "session:${it.id}" }) { session ->
                    LocalSessionDrawerRow(
                        title = session.title,
                        current = session.id == currentSessionId,
                        selected = session.id in selectedIds,
                        selectionOpen = selectionOpen,
                        onClick = {
                            if (selectionOpen) {
                                if (session.id in selectedIds) selectedIds.remove(session.id)
                                else selectedIds.add(session.id)
                            } else onSwitchSession(session.id)
                        },
                        onLongClick = {
                            if (session.id !in selectedIds) selectedIds.add(session.id)
                            selectionOpen = true
                        },
                        onDelete = { onDeleteSessions(setOf(session.id)) },
                    )
                }
                item(key = "drawer-tools-title") {
                    DrawerSectionTitle("功能")
                }
                item(key = "drawer-tools") {
                    DsGroupCard {
                        DsCategoryRow(
                            icon = Icons.Outlined.QrCodeScanner,
                            title = stringResource(R.string.local_remote_control),
                            subtitle = stringResource(R.string.local_remote_hint),
                            onClick = onRemote,
                        )
                        if (usageMode == LocalUsageMode.WORK) {
                            DsCategoryRow(
                                icon = FeatherIcons.FileText,
                                title = stringResource(R.string.chatlist_workspace_files),
                                subtitle = stringResource(R.string.local_files_workspace_subtitle),
                                onClick = onWorkspaceFiles,
                            )
                            DsCategoryRow(
                                icon = Icons.Outlined.Schedule,
                                title = "定时任务",
                                subtitle = "计划任务、周期任务和执行结果",
                                onClick = onTasks,
                            )
                            DsCategoryRow(
                                icon = Icons.Outlined.Extension,
                                title = "工具与连接",
                                subtitle = "外部工具服务与当前扩展",
                                onClick = onTools,
                            )
                        }
                        DsCategoryRow(
                            icon = Icons.Outlined.Settings,
                            title = "设置",
                            subtitle = "模型、个性化、权限与高级选项",
                            onClick = onSettings,
                        )
                        DsCategoryRow(
                            icon = Icons.Outlined.CloudDownload,
                            title = stringResource(R.string.settings_update_check),
                            subtitle = stringResource(R.string.settings_update_check_hint),
                            onClick = onCheckUpdate,
                        )
                    }
                }
                if (updateStatus != null) {
                    item(key = "drawer-update-status") {
                        Text(
                            updateStatus,
                            style = DsType.small13,
                            color = colors.labelSecondary,
                            modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                        )
                    }
                }
            }
            LocalUsageFooter(usage)
        }
    }
    if (selectionOpen) {
        var position by remember { mutableStateOf(IntOffset(24, 160)) }
        Popup(
            alignment = Alignment.TopStart,
            offset = position,
            properties = PopupProperties(focusable = false, clippingEnabled = true),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.bgModulePlatform,
                shadowElevation = 10.dp,
                modifier = Modifier.pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        position = IntOffset(
                            (position.x + drag.x.roundToInt()).coerceAtLeast(0),
                            (position.y + drag.y.roundToInt()).coerceAtLeast(0),
                        )
                    }
                },
            ) {
                Row(
                    Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Text(stringResource(R.string.local_selected_count, selectedIds.size), color = colors.labelPrimary)
                    DsButton(
                        text = stringResource(R.string.local_delete_session),
                        onClick = {
                            val ids = selectedIds.toSet()
                            selectionOpen = false
                            selectedIds.clear()
                            if (ids.isNotEmpty()) onDeleteSessions(ids)
                        },
                        enabled = selectedIds.isNotEmpty(),
                        variant = DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                    DsIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.common_close),
                        onClick = { selectionOpen = false; selectedIds.clear() },
                        tint = colors.labelSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerSectionTitle(title: String) {
    Text(
        title,
        style = DsType.std14,
        color = DsTheme.colors.labelTertiary,
        modifier = Modifier.padding(start = DsSpacing.small, top = DsSpacing.medium, bottom = DsSpacing.tiny),
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LocalSessionDrawerRow(
    title: String,
    current: Boolean,
    selected: Boolean,
    selectionOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = DsTheme.colors
    val density = LocalDensity.current
    val reveal = with(density) { 76.dp.toPx() }
    var swipe by remember { mutableStateOf(0f) }
    Box(Modifier.fillMaxWidth()) {
        DsIconButton(
            icon = Icons.Outlined.DeleteOutline,
            contentDescription = stringResource(R.string.local_delete_session),
            onClick = { swipe = 0f; onDelete() },
            tint = colors.error,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(swipe.roundToInt(), 0) }
            .pointerInput(reveal, selectionOpen) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        swipe = (swipe + amount).coerceIn(0f, reveal)
                    },
                    onDragEnd = { swipe = if (swipe > reveal / 2) reveal else 0f },
                )
            }
            .heightIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.row)
            .background(if (current || selected) colors.sidebarNavActive else colors.sidebar)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionOpen) Checkbox(checked = selected, onCheckedChange = { onClick() })
        Text(
            title,
            style = DsType.std14,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (current) {
            Spacer(Modifier.width(DsSpacing.small))
            Text("当前", style = DsType.caption11, color = colors.accent)
        }
    }
    }
}

@Composable
private fun LocalUsageFooter(usage: DeepSeekUsageSnapshot) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier.padding(
            start = DsSpacing.medium,
            end = DsSpacing.medium,
            bottom = DsSpacing.medium,
        ),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        Text(
            stringResource(R.string.local_usage_title),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
            UsageStatRow(
                label = stringResource(R.string.local_usage_tokens),
                value = formatTokenCount(usage.totalTokens),
            )
            UsageStatRow(
                label = stringResource(R.string.local_usage_cache_hit_rate),
                value = String.format(Locale.US, "%.1f%%", usage.cacheHitRate * 100.0),
            )
            UsageStatRow(
                label = stringResource(R.string.local_usage_estimated_cost),
                value = formatCny(usage.estimatedCostCny),
            )
            Text(
                stringResource(
                    R.string.local_usage_breakdown,
                    formatTokenCount(usage.inputTokens),
                    formatTokenCount(usage.outputTokens),
                    usage.totalRequestCount,
                ),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            if (usage.unreportedRequestCount > 0L) {
                Text(
                    stringResource(R.string.local_usage_unreported, usage.unreportedRequestCount),
                    style = DsType.caption11,
                    color = colors.warnLabel,
                )
            }
            if (usage.unpricedTokens > 0L) {
                Text(
                    stringResource(R.string.local_usage_unpriced, formatTokenCount(usage.unpricedTokens)),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
        }
    }
}

@Composable
private fun UsageStatRow(label: String, value: String) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = DsType.small13, color = colors.labelSecondary)
        Text(value, style = DsType.small13Strong, color = colors.labelPrimary)
    }
}

private fun formatTokenCount(value: Long): String = when {
    value >= 100_000_000L -> String.format(Locale.US, "%.2f亿", value / 100_000_000.0)
    value >= 10_000L -> String.format(Locale.US, "%.1f万", value / 10_000.0)
    else -> value.toString()
}

private fun formatCny(value: Double): String =
    if (value >= 1.0) String.format(Locale.US, "¥%.2f", value)
    else String.format(Locale.US, "¥%.4f", value)

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
        Modifier.fillMaxSize().background(colors.rootSurface()).safeDrawingPadding().imePadding()
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
                ModelChoice("deepseek-flash", "DeepSeek Flash｜V4.1 · 思考默认开启", model) { model = it }
                ModelChoice("deepseek-v4-pro", "DeepSeek V4 Pro｜专家模型", model) { model = it }
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
    onSelectModel: (String) -> Unit,
    onSend: (String, List<LocalImportedAttachment>) -> Unit,
    onRegenerate: (String) -> Boolean,
    onImportAttachment: suspend (android.net.Uri) -> LocalImportedAttachment,
    onImageModeChange: (LocalImageInputMode) -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    onUsageModeChange: (LocalUsageMode) -> Unit,
    onConfigureChatPersona: (PersonaProfile) -> Unit,
    onAutoFillChatPersona: suspend (String) -> Result<PersonaProfile>,
    onPlanModeChange: (Boolean) -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onAutoApprove: () -> Unit,
    onAutoApprovePending: (String) -> Unit,
    onApproveDeviceTurn: (String) -> Unit,
    onDisableDeviceTurn: () -> Unit,
    onDisableAutoApprove: () -> Unit,
    onAnswerQuestion: (String, String) -> Unit,
    onCancelQuestion: (String) -> Unit,
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
    var showImageModePicker by rememberSaveable { mutableStateOf(false) }
    var approvalNoticeExpanded by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showPersonaEditor by rememberSaveable { mutableStateOf(false) }
    var showExecutionConsole by rememberSaveable { mutableStateOf(false) }
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

    val imageLimitMessage = stringResource(R.string.local_image_selection_limit, MAX_LOCAL_IMAGE_SELECTION)
    val imageImportFailedMessage = stringResource(R.string.local_image_import_failed)
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val existingImageCount = attachments.count { it.mediaType.startsWith("image/") }
                val available = (MAX_LOCAL_IMAGE_SELECTION - existingImageCount).coerceAtLeast(0)
                if (available == 0) {
                    attachmentError = imageLimitMessage
                    return@launch
                }
                var failure: String? = if (uris.size > available) imageLimitMessage else null
                uris.take(available).forEach { uri ->
                    runCatching { onImportAttachment(uri) }
                        .onSuccess { imported ->
                            val duplicate = imported.attachmentId != null &&
                                attachments.any { it.attachmentId == imported.attachmentId }
                            if (!duplicate) attachments += imported
                        }
                        .onFailure { error ->
                            failure = error.message ?: imageImportFailedMessage
                        }
                }
                attachmentError = failure
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

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().background(colors.rootSurface())) {
        Column(
            Modifier.fillMaxWidth().background(colors.rootSurface())
                .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DsIconButton(
                    icon = FeatherIcons.Menu,
                    contentDescription = stringResource(R.string.local_open_menu),
                    onClick = onOpenMenu,
                    tint = colors.labelSecondary,
                )
                Spacer(Modifier.width(DsSpacing.small))
                Row(
                    modifier = Modifier.weight(1f)
                        .heightIn(min = DsSpacing.touchTarget)
                        .clip(DsShapes.pillFull)
                        .background(colors.bgModulePlatform)
                        .clickable(enabled = !state.running) {
                            if (state.usageMode == LocalUsageMode.CHAT) {
                                showPersonaEditor = true
                            } else if (state.configured) {
                                showModelPicker = true
                            } else {
                                onConfigure()
                            }
                        }
                        .padding(horizontal = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                ) {
                    Icon(Icons.Outlined.Tune, contentDescription = null, tint = colors.labelSecondary,
                        modifier = Modifier.size(16.dp))
                    Text(
                        if (state.usageMode == LocalUsageMode.CHAT) {
                            state.chatPersona.name
                        } else if (state.configured) {
                            state.model
                        } else {
                            stringResource(R.string.local_model_setup)
                        },
                        style = DsType.small13Strong,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null,
                        tint = colors.labelSecondary, modifier = Modifier.size(16.dp))
                }
                if (state.usageMode == LocalUsageMode.WORK) {
                    DsIconButton(
                        icon = FeatherIcons.FileText,
                        contentDescription = stringResource(R.string.chat_open_files),
                        onClick = onOpenFiles,
                        tint = colors.labelSecondary,
                    )
                    DsIconButton(
                        icon = Icons.Outlined.Terminal,
                        contentDescription = stringResource(R.string.local_execution_console),
                        onClick = { showExecutionConsole = true },
                        tint = colors.labelSecondary,
                    )
                }
                DsIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.chatlist_new_session),
                    onClick = onNewSession,
                    tint = colors.labelSecondary,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = DsSpacing.tiny),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                DsButton(
                    text = stringResource(R.string.local_usage_chat),
                    onClick = { onUsageModeChange(LocalUsageMode.CHAT) },
                    modifier = Modifier.weight(1f),
                    variant = if (state.usageMode == LocalUsageMode.CHAT) DsButtonVariant.Info else DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                    enabled = !state.running,
                )
                DsButton(
                    text = stringResource(R.string.local_usage_work),
                    onClick = { onUsageModeChange(LocalUsageMode.WORK) },
                    modifier = Modifier.weight(1f),
                    variant = if (state.usageMode == LocalUsageMode.WORK) DsButtonVariant.Info else DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                    enabled = !state.running,
                )
            }
        }

        if (state.usageMode == LocalUsageMode.WORK && state.deviceApprovalLease) {
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
                    Column(
                        Modifier.weight(1f)
                            .heightIn(min = DsSpacing.touchTarget)
                            .clickable { approvalNoticeExpanded = !approvalNoticeExpanded },
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            stringResource(R.string.local_device_approval_on),
                            style = DsType.small13Strong,
                            color = colors.warnLabel,
                        )
                        Text(
                            if (approvalNoticeExpanded) stringResource(R.string.local_approval_scope_hide)
                            else stringResource(R.string.local_approval_scope_show),
                            style = DsType.caption11,
                            color = colors.labelSecondary,
                        )
                    }
                    DsButton(
                        "关闭",
                        onDisableDeviceTurn,
                        modifier = Modifier.heightIn(min = DsSpacing.touchTarget),
                        variant = DsButtonVariant.Ghost,
                    )
                }
                if (approvalNoticeExpanded) {
                    Text(
                        stringResource(R.string.local_device_approval_scope),
                        style = DsType.caption11,
                        color = colors.labelSecondary,
                    )
                }
            }
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
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            if (state.usageMode == LocalUsageMode.CHAT) {
                                EmptyLocalChat(state.chatPersona.name)
                            } else {
                                EmptyLocalHarness { suggestion ->
                                    drafts[state.sessionId] = suggestion
                                }
                            }
                        }
                    }
                }
                items(transcriptItems, key = { it.key }) { transcriptItem ->
                    when (transcriptItem) {
                        is LocalTranscriptItem.Message -> LocalMessageRow(
                            transcriptItem.message,
                            canRegenerate = !state.running && state.messages.lastOrNull()?.id == transcriptItem.message.id,
                            onRegenerate = onRegenerate,
                        )
                        is LocalTranscriptItem.WorkProcess -> WorkProcessRow(transcriptItem.messages)
                    }
                }
                if (state.running) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(DsSpacing.small))
                                Text(
                                    stringResource(R.string.local_streaming_status),
                                    style = DsType.small13,
                                    color = colors.labelTertiary,
                                )
                            }
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

        if (state.running && state.streamingAssistant.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                shape = RoundedCornerShape(12.dp),
                color = colors.bgModulePlatform,
            ) {
                Column(
                    Modifier.padding(DsSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                ) {
                    Text(
                        stringResource(R.string.local_streaming_status),
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                    )
                    val preview = state.streamingAssistant
                    Text(
                        if (preview.length > 1_200) "…" + preview.takeLast(1_200) else preview,
                        style = DsType.std14,
                        color = colors.labelPrimary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                        stringResource(R.string.agent_operation_generic) + " · " +
                            stringResource(R.string.agent_operation_status_failed),
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
                            workspacePath = state.workspacePath,
                            onRemove = { attachments.removeAt(index) },
                        )
                    }
                    if (attachments.any { it.mediaType.startsWith("image/") }) {
                        val imageModeLabel = stringResource(
                            when (state.imageInputMode) {
                                LocalImageInputMode.AUTO -> R.string.advanced_image_mode_auto
                                LocalImageInputMode.NATIVE -> R.string.advanced_image_mode_native
                                LocalImageInputMode.TOOL -> R.string.advanced_image_mode_tool
                            },
                        )
                        DsButton(
                            text = stringResource(R.string.local_image_mode_status, imageModeLabel),
                            onClick = { showImageModePicker = true },
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { drafts[state.sessionId] = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            if (state.usageMode == LocalUsageMode.CHAT) stringResource(R.string.local_chat_composer_hint)
                            else "问点什么，或直接交给 Harness 执行…",
                        )
                    },
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
                    if (state.usageMode == LocalUsageMode.WORK) {
                        DsButton(
                            if (state.planMode) "规划中" else "规划",
                            { onPlanModeChange(!state.planMode) },
                            variant = if (state.planMode) DsButtonVariant.Info else DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                            enabled = !state.running,
                        )
                        DsButton(
                            "自动批准",
                            if (state.safeAutoApprovalEnabled) onDisableAutoApprove else onAutoApprove,
                            variant = if (state.safeAutoApprovalEnabled) DsButtonVariant.Info else DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (!state.running) {
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
                if (state.running) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small, Alignment.End),
                    ) {
                        DsButton(
                            "停止",
                            onStop,
                            variant = DsButtonVariant.Danger,
                            size = DsButtonSize.Small,
                        )
                        DsButton(
                            stringResource(R.string.local_queue_message),
                            onClick = {
                                val selected = attachments.toList()
                                onSend(input, selected)
                                drafts[state.sessionId] = ""
                                attachments.clear()
                            },
                            size = DsButtonSize.Small,
                            enabled = input.isNotBlank() || attachments.isNotEmpty(),
                        )
                    }
                }
            }
        }
    }

    state.pendingApproval?.let { approval ->
        ApprovalDialog(
            approval = approval,
            safeAutoApprovalEnabled = state.safeAutoApprovalEnabled,
            onApprove = { onApprove(approval.callId) },
            onDeny = { onDeny(approval.callId) },
            onAutoApprove = { onAutoApprovePending(approval.callId) },
            onApproveDeviceTurn = { onApproveDeviceTurn(approval.callId) },
        )
    }
    state.pendingQuestion?.let { question ->
        QuestionDialog(
            question = question.question,
            options = question.options,
            onAnswer = { answer -> onAnswerQuestion(question.callId, answer) },
            onDismiss = { onCancelQuestion(question.callId) },
        )
    }
    if (showPersonaEditor) {
        ChatPersonaDialog(
            profile = state.chatPersona,
            onSave = onConfigureChatPersona,
            onAutoFill = onAutoFillChatPersona,
            onDismiss = { showPersonaEditor = false },
        )
    }
    if (showModelPicker) {
        DsBottomSheet(title = "选择模型", onDismiss = { showModelPicker = false }) {
            state.configuredModels.forEach { model ->
                DsButton(
                    text = model,
                    onClick = {
                        onSelectModel(model)
                        showModelPicker = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = if (model == state.model) DsButtonVariant.Info else DsButtonVariant.Ghost,
                )
            }
            DsButton(
                text = "管理模型配置",
                onClick = {
                    showModelPicker = false
                    onConfigure()
                },
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Outline,
            )
        }
    }
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
    if (showImageModePicker) {
        DsBottomSheet(
            title = stringResource(R.string.advanced_image_input_mode),
            onDismiss = { showImageModePicker = false },
        ) {
            Text(
                stringResource(R.string.advanced_image_input_hint),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                listOf(
                    LocalImageInputMode.AUTO to R.string.advanced_image_mode_auto,
                    LocalImageInputMode.NATIVE to R.string.advanced_image_mode_native,
                    LocalImageInputMode.TOOL to R.string.advanced_image_mode_tool,
                ).forEach { (mode, label) ->
                    DsButton(
                        text = stringResource(label),
                        onClick = {
                            onImageModeChange(mode)
                            showImageModePicker = false
                        },
                        variant = if (state.imageInputMode == mode) DsButtonVariant.Info else DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                }
            }
        }
    }

    if (showExecutionConsole) {
        Dialog(onDismissRequest = { showExecutionConsole = false }) {
            ExecutionStatusCard(state = state)
        }
    }
}

@Composable
private fun ImportedAttachmentRow(
    attachment: LocalImportedAttachment,
    workspacePath: String,
    onRemove: () -> Unit,
) {
    val colors = DsTheme.colors
    var thumbnail by remember(attachment.relativePath, workspacePath) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(attachment.relativePath, workspacePath) {
        thumbnail = if (attachment.mediaType.startsWith("image/")) {
            withContext(Dispatchers.IO) {
                decodeLocalAttachmentThumbnail(workspacePath, attachment.relativePath)
            }
        } else {
            null
        }
    }
    DsCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            thumbnail?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp).clip(DsShapes.block),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(attachment.name, style = DsType.small13Strong, color = colors.labelPrimary)
                val dimensions = if (attachment.width != null && attachment.height != null) {
                    " · ${attachment.width}×${attachment.height}"
                } else {
                    ""
                }
                Text(
                    "${attachment.mediaType}$dimensions · ${attachment.bytes} B",
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
            DsButton("移除", onRemove, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
        }
    }
}

@Composable
private fun EmptyLocalChat(personaName: String) {
    val colors = DsTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DsSpacing.large, vertical = DsSpacing.xlarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        WhaleMark(Modifier.size(40.dp))
        Text(personaName, style = DsType.display24, color = colors.labelPrimary)
        Text(
            stringResource(R.string.local_chat_empty_hint),
            style = DsType.std14,
            color = colors.labelSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyLocalHarness(onSuggestion: (String) -> Unit) {
    val colors = DsTheme.colors
    val filesPrompt = stringResource(R.string.local_prompt_files)
    val researchPrompt = stringResource(R.string.local_prompt_research)
    val tasksPrompt = stringResource(R.string.local_prompt_tasks)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DsSpacing.small, vertical = DsSpacing.xlarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        WhaleMark(Modifier.size(40.dp))
        Text(stringResource(R.string.local_welcome_title), style = DsType.display24, color = colors.labelPrimary)
        Text(
            stringResource(R.string.local_welcome_hint),
            style = DsType.std14,
            color = colors.labelSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(stringResource(R.string.local_suggestions_title), style = DsType.small13Strong, color = colors.labelTertiary)
        DsGroupCard {
            DsCategoryRow(
                icon = FeatherIcons.FileText,
                title = stringResource(R.string.local_suggestion_files),
                subtitle = stringResource(R.string.local_suggestion_files_hint),
                onClick = { onSuggestion(filesPrompt) },
            )
            DsCategoryRow(
                icon = FeatherIcons.Globe,
                title = stringResource(R.string.local_suggestion_research),
                subtitle = stringResource(R.string.local_suggestion_research_hint),
                onClick = { onSuggestion(researchPrompt) },
            )
            DsCategoryRow(
                icon = FeatherIcons.CheckSquare,
                title = stringResource(R.string.local_suggestion_tasks),
                subtitle = stringResource(R.string.local_suggestion_tasks_hint),
                onClick = { onSuggestion(tasksPrompt) },
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
    val completed = state.todos.count { it.status == "completed" }
    val total = state.todos.size
    val resourceSummary = stringResource(
        R.string.local_resource_summary,
        state.activeAgents,
        state.maxAgents,
        state.activeTerminals,
        state.maxTerminals,
        state.activeVirtualDisplays,
        state.maxVirtualDisplays,
        state.activeLanguageServers,
        state.maxLanguageServers,
    )
    val contextSummary = stringResource(
        R.string.local_context_summary,
        state.contextChars,
        state.contextBudgetChars,
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.bgModulePlatform,
    ) {
        Column(
            Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Text(stringResource(R.string.local_execution_console), style = DsType.base16Strong, color = colors.labelPrimary)
            state.goal?.let { goal ->
                Text("目标 · ${goal.status}", style = DsType.small13, color = colors.labelSecondary)
            }
            if (state.plan.isNotEmpty()) {
                Text("计划 · ${state.plan.size} 步", style = DsType.small13, color = colors.labelSecondary)
            }
            if (state.todos.isNotEmpty()) {
                Text("任务 · $completed/$total", style = DsType.small13, color = colors.labelSecondary)
            }
            Text(resourceSummary, style = DsType.caption11, color = colors.labelTertiary)
            Text(contextSummary, style = DsType.caption11, color = colors.labelTertiary)
            if (state.jobs.isNotEmpty()) {
                Text(
                    "后台 · " + state.jobs.joinToString { it.status },
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
        }
    }
}

@Composable
private fun LocalMessageRow(
    message: LocalHarnessMessage,
    canRegenerate: Boolean,
    onRegenerate: (String) -> Boolean,
) {
    val colors = DsTheme.colors
    val clipboard = LocalClipboardManager.current

    when (message.role) {
        "user" -> UserBubble(message.content)

        "system" -> Unit

        "reasoning", "tool", "progress" -> WorkProcessRow(listOf(message))

        else -> Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            MarkdownText(message.content)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                DsIconButton(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.chat_copy_answer),
                    onClick = { clipboard.setText(AnnotatedString(message.content)) },
                    tint = colors.labelTertiary,
                )
                if (canRegenerate) DsIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.local_regenerate_reply),
                    onClick = { onRegenerate(message.id) },
                    tint = colors.labelTertiary,
                )
            }
        }
    }
}

@Composable
private fun WorkProcessRow(messages: List<LocalHarnessMessage>) {
    if (messages.isEmpty()) return

    val colors = DsTheme.colors
    val toolMessages = messages.filter { it.role == "tool" }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.bgModulePlatform,
    ) {
        Column(
            Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            Text("工作过程", style = DsType.small13Strong, color = colors.labelSecondary)
            if (toolMessages.isEmpty()) {
                Text(
                    stringResource(R.string.agent_operation_generic) + " · " +
                        stringResource(R.string.agent_operation_status_running),
                    style = DsType.small13,
                    color = colors.labelTertiary,
                )
            } else {
                val grouped = linkedMapOf<com.labteto.dshmobile.ui.AgentOperationKind, MutableList<LocalHarnessMessage>>()
                toolMessages.forEach { message ->
                    grouped.getOrPut(agentOperationKind(message.toolName)) { mutableListOf() }.add(message)
                }
                grouped.values.forEach { group ->
                    val failed = group.any { toolResultFailed(it.content) }
                    val exemplar = group.first()
                    Text(
                        stringResource(agentOperationLabelRes(exemplar.toolName)) + " · " +
                            stringResource(agentOperationStatusRes(running = false, failed = failed)),
                        style = DsType.small13,
                        color = if (failed) colors.error else colors.labelTertiary,
                    )
                }
            }
        }
    }
}

private fun toolResultFailed(content: String): Boolean =
    "工具执行失败" in content || "[TOOL_TIMEOUT]" in content || "[MODEL_TIMEOUT]" in content ||
        "[NETWORK_ERROR]" in content || "[DNS_FAILED]" in content || "[SSRF_BLOCKED]" in content

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

private const val MAX_LOCAL_IMAGE_SELECTION = 20


private fun decodeLocalAttachmentThumbnail(
    workspacePath: String,
    relativePath: String,
): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val root = File(workspacePath).canonicalFile
    val file = File(root, relativePath).canonicalFile
    require(file.toPath().startsWith(root.toPath()) && file.isFile)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 160 || bounds.outHeight / (sample * 2) >= 160) {
        sample *= 2
    }
    BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )?.asImageBitmap()
}.getOrNull()
