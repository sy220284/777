package com.labteto.dshmobile.ui.screens.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.rootSurface
import java.text.DateFormat
import java.util.Date

@Composable
fun TasksScreen(
    onClose: () -> Unit,
    onOpenSession: (String) -> Unit = {},
    initialMode: AutomationMode? = null,
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
        )
        return
    }

    WorkTasksScreen(
        state = state,
        viewModel = viewModel,
        onClose = onClose,
        onOpenSession = onOpenSession,
        handleRootSystemBack = handleRootSystemBack,
    )
}

@Composable
private fun WorkTasksScreen(
    state: TasksUiState,
    viewModel: TasksViewModel,
    onClose: () -> Unit,
    onOpenSession: (String) -> Unit,
    handleRootSystemBack: Boolean,
) {
    val colors = DsTheme.colors
    val visibleTasks = state.tasks.filter { it.mode == AutomationMode.WORK }
    val createInvalidMessage = stringResource(R.string.tasks_create_invalid)
    var showCreate by rememberSaveable { mutableStateOf(false) }
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
    val navigateBack = { if (showCreate) resetEditor() else onClose() }
    BackHandler(enabled = showCreate || handleRootSystemBack) { navigateBack() }

    val toast = rememberDsToast()
    val noticeMessage = state.notice?.let { notice ->
        stringResource(
            when (notice) {
                TasksNotice.CANCELLED -> R.string.tasks_cancelled
                TasksNotice.MISSING -> R.string.tasks_missing
                TasksNotice.RUN_STARTED -> R.string.tasks_run_started
                TasksNotice.RUN_FAILED -> R.string.tasks_run_now_failed
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
                    .safeDrawingPadding()
                    .padding(horizontal = DsSpacing.large, vertical = DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                DsTopBar(
                    title = stringResource(R.string.tasks_title),
                    onBack = navigateBack,
                    backContentDescription = stringResource(R.string.common_back),
                    largeTitle = false,
                    actionIcon = null,
                    actionPainter = if (showCreate) null else painterResource(R.drawable.ic_kimi_add),
                    actionContentDescription = stringResource(R.string.tasks_new),
                    onAction = ::startCreate,
                )

                when {
                    showCreate -> TaskEditorPane(
                        state = editorState,
                        viewModel = viewModel,
                        createInvalidMessage = createInvalidMessage,
                        onReset = ::resetEditor,
                    )
                    visibleTasks.isEmpty() -> KimiTaskEmptyState(
                        onManualCreate = ::startCreate,
                        onCreateViaChat = onClose,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        items(visibleTasks, key = AutomationTask::id) { task ->
                            TaskCard(
                                task = task,
                                onCancel = { viewModel.cancel(task.id) },
                                onPause = { viewModel.pause(task.id) },
                                onResume = { viewModel.resume(task.id) },
                                onRunNow = { viewModel.runNow(task.id) },
                                onEdit = {
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
                                },
                                onOpenSession = onOpenSession,
                            )
                        }
                    }
                }
            }
        }
        DsToastHost(toast, Modifier.safeDrawingPadding())
    }
}

@Composable
private fun KimiTaskEmptyState(
    onManualCreate: () -> Unit,
    onCreateViaChat: () -> Unit,
) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = DsSpacing.xlarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_kimi_task_empty),
            contentDescription = null,
            modifier = Modifier.padding(bottom = DsSpacing.medium),
        )
        Text(
            stringResource(R.string.tasks_empty_title),
            style = DsType.base16Strong.withReadingWeight(),
            color = colors.labelPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.tasks_empty_subtitle),
            style = DsType.small13.withReadingWeight(),
            color = colors.labelTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(
                top = DsSpacing.xsmall,
                bottom = DsSpacing.large,
            ),
        )
        DsButton(
            text = stringResource(R.string.tasks_create_manual),
            onClick = onManualCreate,
            variant = DsButtonVariant.Info,
            size = DsButtonSize.Normal,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.tasks_empty_or),
            style = DsType.caption11.withReadingWeight(),
            color = colors.labelCaption,
            modifier = Modifier.padding(vertical = DsSpacing.xsmall),
        )
        DsButton(
            text = stringResource(R.string.tasks_create_via_chat),
            onClick = onCreateViaChat,
            variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Normal,
            modifier = Modifier.fillMaxWidth(),
        )
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
