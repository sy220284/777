package com.labteto.dshmobile.ui.screens.tasks

import androidx.compose.foundation.shape.RoundedCornerShape

import com.shenyan.designsystem.foundation.SyMetric



import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationStatus
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SySegment
import com.labteto.dshmobile.ui.shenyan.SySegmented
import com.labteto.dshmobile.ui.shenyan.SyToastHost
import com.labteto.dshmobile.ui.shenyan.SyTopBar
import com.shenyan.designsystem.components.SyIconButton
import com.labteto.dshmobile.ui.shenyan.SyPopupMenu
import com.labteto.dshmobile.ui.shenyan.SyMenuItem
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.shenyan.rememberSyToast
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.rootSurface
import java.text.DateFormat
import java.util.Date

/** Device-side view filter only; never changes the persisted task's real status. */
internal enum class WorkTaskListFilter { ALL, SCHEDULED, RUNNING, PAUSED }

internal fun workTaskMatchesFilter(status: AutomationStatus, filter: WorkTaskListFilter): Boolean =
    when (filter) {
        WorkTaskListFilter.ALL -> true
        WorkTaskListFilter.SCHEDULED ->
            status == AutomationStatus.SCHEDULED || status == AutomationStatus.QUEUED
        WorkTaskListFilter.RUNNING ->
            status == AutomationStatus.RUNNING || status == AutomationStatus.WAITING_USER
        WorkTaskListFilter.PAUSED -> status == AutomationStatus.PAUSED
    }

@Composable
fun TasksScreen(
    onClose: () -> Unit,
    onOpenSession: (String) -> Unit = {},
    onCreateViaChat: ((String) -> Unit)? = null,
    initialMode: AutomationMode? = null,
    initialSelectedTaskId: String? = null,
    entryRequest: Int = 0,
    handleRootSystemBack: Boolean = true,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val harnessState by viewModel.harnessState.collectAsStateWithLifecycle()
    val resolvedMode = initialMode ?: if (harnessState.usageMode == LocalUsageMode.CHAT) {
        AutomationMode.CHAT
    } else {
        AutomationMode.WORK
    }
    if (resolvedMode == AutomationMode.CHAT) {
        ChatAutomationScreen(
            state = state,
            harnessState = harnessState,
            viewModel = viewModel,
            onClose = onClose,
            onOpenSession = onOpenSession,
            handleRootSystemBack = handleRootSystemBack,
            initialSelectedTaskId = initialSelectedTaskId,
            entryRequest = entryRequest,
        )
        return
    }

    WorkTasksScreen(
        state = state,
        viewModel = viewModel,
        onClose = onClose,
        onOpenSession = onOpenSession,
        onCreateViaChat = onCreateViaChat,
        handleRootSystemBack = handleRootSystemBack,
        initialSelectedTaskId = initialSelectedTaskId,
        entryRequest = entryRequest,
    )
}

@Composable
private fun WorkTasksScreen(
    state: TasksUiState,
    viewModel: TasksViewModel,
    onClose: () -> Unit,
    onOpenSession: (String) -> Unit,
    onCreateViaChat: ((String) -> Unit)?,
    handleRootSystemBack: Boolean,
    initialSelectedTaskId: String?,
    entryRequest: Int,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val visibleTasks = state.tasks.filter { it.mode == AutomationMode.WORK }
    var listFilter by rememberSaveable { mutableStateOf(WorkTaskListFilter.ALL) }
    val filteredTasks = visibleTasks.filter { workTaskMatchesFilter(it.status, listFilter) }
    val chatCreatePrompt = stringResource(R.string.tasks_create_chat_prompt)
    val createFromChat = { if (onCreateViaChat != null) onCreateViaChat(chatCreatePrompt) else Unit }
    val createInvalidMessage = stringResource(R.string.tasks_create_invalid)
    var showCreate by com.labteto.dshmobile.ui.components.rememberNavigationEntryState(false, entryRequest)
    var selectedTaskId by com.labteto.dshmobile.ui.components.rememberNavigationEntryState(initialSelectedTaskId, entryRequest)
    val selectedTask = visibleTasks.firstOrNull { it.id == selectedTaskId }
    LaunchedEffect(visibleTasks, selectedTaskId) {
        if (selectedTaskId != null && selectedTask == null) selectedTaskId = null
    }
    var createMenuOpen by rememberSaveable { mutableStateOf(false) }
    val editingTaskIdState = rememberSaveable { mutableStateOf<String?>(null) }
    val promptState = rememberSaveable { mutableStateOf("") }
    val cadenceState = rememberSaveable { mutableStateOf(AutomationCadence.ONCE) }
    val firstRunAtState = rememberSaveable {
        mutableStateOf(System.currentTimeMillis() + 60L * 60_000L)
    }
    val customHoursState = rememberSaveable { mutableStateOf("6") }
    val notifyState = rememberSaveable { mutableStateOf(true) }
    val createErrorState = rememberSaveable { mutableStateOf<String?>(null) }
    val editorState = TaskEditorStateRefs(
        editingTaskId = editingTaskIdState,
        prompt = promptState,
        cadence = cadenceState,
        firstRunAt = firstRunAtState,
        customHours = customHoursState,
        notify = notifyState,
        createError = createErrorState,
    )
    fun resetEditor() {
        showCreate = false
        editingTaskIdState.value = null
        promptState.value = ""
        cadenceState.value = AutomationCadence.ONCE
        firstRunAtState.value = System.currentTimeMillis() + 60L * 60_000L
        customHoursState.value = "6"
        notifyState.value = true
        createErrorState.value = null
    }
    fun startCreate() {
        resetEditor()
        showCreate = true
    }
    fun startEdit(task: AutomationTask) {
        editingTaskIdState.value = task.id
        promptState.value = task.prompt
        cadenceState.value = cadenceForTask(task)
        firstRunAtState.value = maxOf(
            task.nextRunAt,
            System.currentTimeMillis() + 60_000L,
        )
        customHoursState.value = customHoursForTask(task)
        notifyState.value = task.notify
        createErrorState.value = null
        showCreate = true
    }
    val navigateBack = { if (showCreate) resetEditor() else if (selectedTaskId != null) selectedTaskId = null else onClose() }
    BackHandler(enabled = showCreate || selectedTaskId != null || handleRootSystemBack) { navigateBack() }

    val toast = rememberSyToast()
    val noticeMessage = state.notice?.let { notice ->
        stringResource(
            when (notice) {
                TasksNotice.CANCELLED -> R.string.tasks_cancelled
                TasksNotice.MISSING -> R.string.tasks_missing
                TasksNotice.RUN_STARTED -> R.string.tasks_run_started
                TasksNotice.RUN_FAILED -> R.string.tasks_run_now_failed
                TasksNotice.PAUSED -> R.string.tasks_paused_notice
                TasksNotice.RESUMED -> R.string.tasks_resumed_notice
                TasksNotice.OPERATION_FAILED -> R.string.tasks_operation_failed
                TasksNotice.OPERATION_REJECTED -> R.string.tasks_operation_rejected
            },
        )
    }
    LaunchedEffect(state.notice, noticeMessage) {
        state.notice?.let { notice ->
            noticeMessage?.let(toast.second)
            viewModel.acknowledgeNotice(notice)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = colors.rootSurface()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    SyTopBar(
                        title = stringResource(
                            if (showCreate) {
                                if (editingTaskIdState.value != null) R.string.tasks_edit else R.string.tasks_new
                            } else if (selectedTask != null) R.string.tasks_details else R.string.tasks_title,
                        ),
                        onBack = navigateBack,
                        backContentDescription = stringResource(R.string.common_back),
                        largeTitle = false,
                        backIcon = if (showCreate || selectedTask != null) FeatherIcons.ChevronLeft else FeatherIcons.X,
                    )
                    if (!showCreate && selectedTask == null) {
                        Box(Modifier.align(Alignment.CenterEnd)) {
                            SyIconButton(
                                icon = FeatherIcons.Plus,
                                contentDescription = stringResource(R.string.tasks_new),
                                onClick = { createMenuOpen = true },
                                tint = colors.text,
                            )
                            SyPopupMenu(
                                expanded = createMenuOpen,
                                onDismiss = { createMenuOpen = false },
                                items = listOf(
                                    SyMenuItem(
                                        text = stringResource(R.string.tasks_create_manual),
                                        icon = FeatherIcons.Edit3,
                                        onClick = ::startCreate,
                                    ),
                                ) + if (onCreateViaChat != null) listOf(
                                    SyMenuItem(
                                        text = stringResource(R.string.tasks_create_via_chat),
                                        icon = FeatherIcons.MessageCircle,
                                        onClick = createFromChat,
                                    ),
                                ) else emptyList(),
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f)
                        .padding(horizontal = SyMetric.gapSection, vertical = SyMetric.gapLg),
                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
                ) {
                    if (state.managementFailed) {
                        Text(stringResource(R.string.tasks_operation_failed), color = colors.dangerInk)
                        SyButton(stringResource(R.string.common_retry), onClick = { viewModel.refresh() })
                    }
                    when {
                        state.loading && state.tasks.isEmpty() -> Text(stringResource(R.string.common_loading))
                        showCreate -> TaskEditorPane(
                            state = editorState,
                            viewModel = viewModel,
                            createInvalidMessage = createInvalidMessage,
                            saving = state.savingEditor,
                            onReset = ::resetEditor,
                        )
                        visibleTasks.isEmpty() -> TaskEmptyState(
                            onManualCreate = ::startCreate,
                            onCreateViaChat = onCreateViaChat?.let { createFromChat },
                        )
                        selectedTask != null -> Column(
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        ) {
                            TaskCard(
                                task = selectedTask,
                                enabled = selectedTask.id !in state.busyIds,
                                onCancel = { viewModel.cancel(selectedTask.id) },
                                onPause = { viewModel.pause(selectedTask.id) },
                                onResume = { viewModel.resume(selectedTask.id) },
                                onRunNow = { viewModel.runNow(selectedTask.id) },
                                onEdit = { startEdit(selectedTask) },
                                onOpenSession = onOpenSession,
                            )
                        }
                        else -> Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
                        ) {
                            // Screen 19: show the real schedule state in the same glass tab
                            // language as the work/history pages; never fabricate budget figures.
                            val statusFilters = WorkTaskListFilter.entries
                            val titles = listOf(
                                stringResource(R.string.tasks_filter_all),
                                stringResource(R.string.tasks_filter_scheduled),
                                stringResource(R.string.tasks_filter_running),
                                stringResource(R.string.tasks_filter_paused),
                            )
                            SySegmented(
                                segments = statusFilters.mapIndexed { index, filter ->
                                    SySegment(filter.name, "${titles[index]} · ${visibleTasks.count { workTaskMatchesFilter(it.status, filter) }}")
                                },
                                selectedKey = listFilter.name,
                                onSelect = { key ->
                                    listFilter = WorkTaskListFilter.entries.firstOrNull { it.name == key }
                                        ?: WorkTaskListFilter.ALL
                                },
                                role = Role.Tab,
                                stretch = true,
                                hapticOnChange = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (filteredTasks.isEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(SyMetric.gapSection),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                                ) {
                                    Text(
                                        stringResource(R.string.tasks_filter_empty),
                                        style = DsType.small13.withReadingWeight(),
                                        color = colors.textSecondary,
                                    )
                                    SyButton(
                                        text = stringResource(R.string.tasks_filter_all),
                                        onClick = { listFilter = WorkTaskListFilter.ALL },
                                        variant = SyButtonVariant.Outline,
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = SyMetric.gapLg),
                                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
                                ) {
                                    items(filteredTasks, key = AutomationTask::id) { task ->
                                        TaskSummaryRow(task = task, onClick = { selectedTaskId = task.id })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SyToastHost(toast, Modifier.safeDrawingPadding())
    }
}

@Composable
private fun TaskEmptyState(
    onManualCreate: () -> Unit,
    onCreateViaChat: (() -> Unit)?,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                .padding(horizontal = SyMetric.gapPage),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_ui_task_empty),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.textTertiary.copy(alpha = 0.72f)),
                modifier = Modifier.padding(bottom = SyMetric.gapLg),
            )
            Text(
                stringResource(R.string.tasks_empty_title),
                style = DsType.base16Strong.withReadingWeight(),
                color = colors.text,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.tasks_empty_subtitle),
                style = DsType.small13.withReadingWeight(),
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(bottom = SyMetric.gapLg),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Surface(
                onClick = onManualCreate,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                color = colors.primary,
                contentColor = colors.onPrimary,
                shape = RoundedCornerShape(SyMetric.radiusControl),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(SyMetric.gapLg)) {
                    Text(
                        stringResource(R.string.tasks_create_manual),
                        style = DsType.std14Strong.withReadingWeight(),
                        color = colors.onPrimary,
                    )
                }
            }
            if (onCreateViaChat != null) Surface(
                onClick = onCreateViaChat,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                color = androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(SyMetric.radiusControl),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(SyMetric.gapLg)) {
                    Text(
                        stringResource(R.string.tasks_create_via_chat),
                        style = DsType.std14Strong.withReadingWeight(),
                        color = colors.text,
                    )
                }
            }
        }
    }
}

private fun cadenceForTask(task: AutomationTask): AutomationCadence = when (task.scheduleType) {
    AutomationScheduleType.ONCE -> AutomationCadence.ONCE
    AutomationScheduleType.DAILY -> AutomationCadence.DAILY
    AutomationScheduleType.WEEKLY -> AutomationCadence.WEEKLY
    AutomationScheduleType.MONTHLY -> AutomationCadence.MONTHLY
    AutomationScheduleType.INTERVAL -> AutomationCadence.CUSTOM
    AutomationScheduleType.LEGACY -> when (task.recurringMinutes) {
        null -> AutomationCadence.ONCE
        24L * 60L -> AutomationCadence.DAILY
        7L * 24L * 60L -> AutomationCadence.WEEKLY
        else -> AutomationCadence.CUSTOM
    }
    AutomationScheduleType.SILENCE, AutomationScheduleType.WINDOW -> AutomationCadence.CUSTOM
}

private fun customHoursForTask(task: AutomationTask): String =
    maxOf(1L, (task.recurringMinutes ?: 6L * 60L) / 60L).toString()

internal fun formatTime(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
