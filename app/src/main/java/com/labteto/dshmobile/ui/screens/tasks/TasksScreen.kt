package com.labteto.dshmobile.ui.screens.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.rootSurface
import java.text.DateFormat
import java.util.Date

@Composable
fun TasksScreen(
    onClose: () -> Unit,
    onOpenSession: (String) -> Unit = {},
    initialMode: AutomationMode? = null,
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
        )
        return
    }

    WorkTasksScreen(
        state = state,
        viewModel = viewModel,
        onClose = onClose,
        onOpenSession = onOpenSession,
    )
}

@Composable
private fun WorkTasksScreen(
    state: TasksUiState,
    viewModel: TasksViewModel,
    onClose: () -> Unit,
    onOpenSession: (String) -> Unit,
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
    val createErrorState = rememberSaveable { mutableStateOf<String?>(null) }
    val editorState = TaskEditorStateRefs(
        editingTaskId = editingTaskIdState,
        prompt = promptState,
        cadence = cadenceState,
        firstRunAt = firstRunAtState,
        customHours = customHoursState,
        createError = createErrorState,
    )
    fun resetEditor() {
        showCreate = false
        editingTaskIdState.value = null
        promptState.value = ""
        cadenceState.value = AutomationCadence.ONCE
        firstRunAtState.value = System.currentTimeMillis() + 60L * 60_000L
        customHoursState.value = "6"
        createErrorState.value = null
    }
    fun startCreate() {
        resetEditor()
        showCreate = true
    }
    val navigateBack = { if (showCreate) resetEditor() else onClose() }
    BackHandler { navigateBack() }

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
                verticalArrangement = Arrangement.spacedBy(DsSpacing.large),
            ) {
                DsTopBar(
                    title = stringResource(R.string.tasks_title),
                    subtitle = stringResource(R.string.tasks_subtitle),
                    onBack = navigateBack,
                    backContentDescription = stringResource(R.string.common_back),
                    largeTitle = true,
                    actionIcon = if (showCreate) null else FeatherIcons.Plus,
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
                    visibleTasks.isEmpty() -> EmptyHero(
                        headline = stringResource(R.string.tasks_empty_title),
                        subtitle = stringResource(R.string.tasks_empty_subtitle),
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

private fun cadenceForTask(task: AutomationTask): AutomationCadence = when (task.scheduleType) {
    AutomationScheduleType.ONCE -> AutomationCadence.ONCE
    AutomationScheduleType.DAILY -> AutomationCadence.DAILY
    AutomationScheduleType.WEEKLY -> AutomationCadence.WEEKLY
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
