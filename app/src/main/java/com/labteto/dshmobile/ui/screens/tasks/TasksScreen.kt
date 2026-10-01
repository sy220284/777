package com.labteto.dshmobile.ui.screens.tasks

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat as AndroidDateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationRunReceipt
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.automation.HarnessAutomationScheduler
import com.labteto.dshmobile.local.presentation.LocalTaskRuntime
import com.labteto.dshmobile.local.presentation.LocalHarnessTaskState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.components.DsTimeline
import com.labteto.dshmobile.ui.components.DsTimelineItem
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.rootSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

enum class TasksNotice { CANCELLED, MISSING, RUN_STARTED, RUN_FAILED }

internal enum class AutomationCadence { ONCE, DAILY, WEEKLY, CUSTOM, SILENCE, WINDOW }

data class TasksUiState(
    val tasks: List<AutomationTask> = emptyList(),
    val notice: TasksNotice? = null,
)

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val scheduler: HarnessAutomationScheduler,
    private val localRuntime: LocalTaskRuntime,
) : ViewModel() {
    val harnessState: StateFlow<LocalHarnessTaskState> = localRuntime.state
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = localRuntime.initialState,
        )
    private val _state = MutableStateFlow(TasksUiState())
    val state: StateFlow<TasksUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun acknowledgeNotice(notice: TasksNotice) {
        if (_state.value.notice == notice) {
            _state.value = _state.value.copy(notice = null)
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(tasks = scheduler.list(), notice = null)
    }

    fun cancel(id: String) {
        val removed = scheduler.cancelTask(id)
        _state.value = TasksUiState(
            tasks = scheduler.list(),
            notice = if (removed) TasksNotice.CANCELLED else TasksNotice.MISSING,
        )
    }

    fun pause(id: String) {
        scheduler.pauseTask(id)
        refresh()
    }

    fun resume(id: String) {
        scheduler.resumeTask(id)
        refresh()
    }

    fun runNow(id: String): Boolean {
        val started = scheduler.runTaskNow(id)
        _state.value = TasksUiState(
            tasks = scheduler.list(),
            notice = if (started) TasksNotice.RUN_STARTED else TasksNotice.RUN_FAILED,
        )
        return started
    }

    fun updateTask(
        id: String,
        prompt: String,
        firstRunAt: Long,
        recurringMinutes: Long?,
        scheduleType: AutomationScheduleType,
        silenceMinutes: Long?,
        windowStartMinuteOfDay: Int?,
        windowEndMinuteOfDay: Int?,
        quietHoursEnabled: Boolean,
        quietStartHour: Int,
        quietStartMinute: Int,
        quietEndHour: Int,
        quietEndMinute: Int,
        proactiveMinGapMinutes: Long,
        proactiveMaxUnanswered: Int,
    ): Boolean = runCatching {
        scheduler.updateTask(
            id = id,
            prompt = prompt,
            firstRunAtMillis = firstRunAt,
            recurringMinutes = recurringMinutes,
            scheduleType = scheduleType,
            silenceMinutes = silenceMinutes,
            windowStartMinuteOfDay = windowStartMinuteOfDay,
            windowEndMinuteOfDay = windowEndMinuteOfDay,
            quietHoursEnabled = quietHoursEnabled,
            quietStartHour = quietStartHour,
            quietStartMinute = quietStartMinute,
            quietEndHour = quietEndHour,
            quietEndMinute = quietEndMinute,
            proactiveMinGapMinutes = proactiveMinGapMinutes,
            proactiveMaxUnanswered = proactiveMaxUnanswered,
        )
    }.getOrDefault(false).also {
        if (it) refresh()
    }

    fun createAt(
        prompt: String,
        firstRunAt: Long,
        recurringMinutes: Long?,
        mode: AutomationMode,
        scheduleType: AutomationScheduleType,
        silenceMinutes: Long? = null,
        windowStartMinuteOfDay: Int? = null,
        windowEndMinuteOfDay: Int? = null,
        quietHoursEnabled: Boolean = false,
        quietStartHour: Int = 23,
        quietStartMinute: Int = 0,
        quietEndHour: Int = 7,
        quietEndMinute: Int = 0,
        proactiveMinGapMinutes: Long = 6L * 60L,
        proactiveMaxUnanswered: Int = 2,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (prompt.isBlank()) return false
        if (
            scheduleType !in setOf(
                AutomationScheduleType.SILENCE,
                AutomationScheduleType.WINDOW,
            ) &&
            firstRunAt <= now
        ) return false
        val minimumRecurringMinutes = if (mode == AutomationMode.CHAT) 60L else 15L
        if (recurringMinutes != null && recurringMinutes < minimumRecurringMinutes) return false
        if (
            scheduleType == AutomationScheduleType.SILENCE &&
            (silenceMinutes == null || silenceMinutes < 60L)
        ) return false
        if (
            scheduleType == AutomationScheduleType.WINDOW &&
            (
                windowStartMinuteOfDay == null ||
                    windowEndMinuteOfDay == null ||
                    windowStartMinuteOfDay !in 0 until 24 * 60 ||
                    windowEndMinuteOfDay !in 0 until 24 * 60 ||
                    windowStartMinuteOfDay == windowEndMinuteOfDay
            )
        ) return false
        val snapshot = localRuntime.snapshot()
        if (mode == AutomationMode.CHAT) {
            if (
                snapshot.usageMode != LocalUsageMode.CHAT ||
                snapshot.groupChat.enabled ||
                snapshot.sessionId.isBlank()
            ) return false
        }
        return runCatching {
            val id = "ui-" + System.currentTimeMillis()
            val targetSessionId = snapshot.sessionId.takeIf { mode == AutomationMode.CHAT }
            val actorName = snapshot.chatPersona.name.takeIf {
                mode == AutomationMode.CHAT && it.isNotBlank()
            }
            when {
                scheduleType == AutomationScheduleType.WINDOW -> scheduler.scheduleWindow(
                    id = id,
                    prompt = prompt.trim(),
                    startMinuteOfDay = requireNotNull(windowStartMinuteOfDay),
                    endMinuteOfDay = requireNotNull(windowEndMinuteOfDay),
                    notify = true,
                    targetSessionId = requireNotNull(targetSessionId),
                    actorName = actorName,
                    quietHoursEnabled = quietHoursEnabled,
                    quietStartHour = quietStartHour,
                    quietStartMinute = quietStartMinute,
                    quietEndHour = quietEndHour,
                    quietEndMinute = quietEndMinute,
                    proactiveMinGapMinutes = proactiveMinGapMinutes,
                    proactiveMaxUnanswered = proactiveMaxUnanswered,
                )
                scheduleType == AutomationScheduleType.SILENCE -> scheduler.scheduleSilence(
                    id = id,
                    prompt = prompt.trim(),
                    silenceMinutes = requireNotNull(silenceMinutes),
                    notify = true,
                    targetSessionId = requireNotNull(targetSessionId),
                    actorName = actorName,
                    quietHoursEnabled = quietHoursEnabled,
                    quietStartHour = quietStartHour,
                    quietStartMinute = quietStartMinute,
                    quietEndHour = quietEndHour,
                    quietEndMinute = quietEndMinute,
                    proactiveMinGapMinutes = proactiveMinGapMinutes,
                    proactiveMaxUnanswered = proactiveMaxUnanswered,
                )
                recurringMinutes == null -> scheduler.scheduleOnce(
                    id = id,
                    prompt = prompt.trim(),
                    triggerAtMillis = firstRunAt,
                    notify = true,
                    mode = mode,
                    targetSessionId = targetSessionId,
                    actorName = actorName,
                    quietHoursEnabled = quietHoursEnabled,
                    quietStartHour = quietStartHour,
                    quietStartMinute = quietStartMinute,
                    quietEndHour = quietEndHour,
                    quietEndMinute = quietEndMinute,
                    proactiveMinGapMinutes = proactiveMinGapMinutes,
                    proactiveMaxUnanswered = proactiveMaxUnanswered,
                )
                else -> scheduler.schedulePeriodic(
                    id = id,
                    prompt = prompt.trim(),
                    intervalMinutes = recurringMinutes,
                    firstRunAtMillis = firstRunAt,
                    notify = true,
                    mode = mode,
                    targetSessionId = targetSessionId,
                    actorName = actorName,
                    quietHoursEnabled = quietHoursEnabled,
                    quietStartHour = quietStartHour,
                    quietStartMinute = quietStartMinute,
                    quietEndHour = quietEndHour,
                    quietEndMinute = quietEndMinute,
                    proactiveMinGapMinutes = proactiveMinGapMinutes,
                    proactiveMaxUnanswered = proactiveMaxUnanswered,
                    scheduleType = if (mode == AutomationMode.CHAT) {
                        scheduleType
                    } else {
                        AutomationScheduleType.LEGACY
                    },
                )
            }
            refresh()
        }.isSuccess
    }

}

@Composable
fun TasksScreen(
    onClose: () -> Unit,
    onOpenSession: (String) -> Unit = {},
    initialMode: AutomationMode? = null,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val harnessState by viewModel.harnessState.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    val harnessTaskMode = if (harnessState.usageMode == LocalUsageMode.CHAT) {
        AutomationMode.CHAT
    } else {
        AutomationMode.WORK
    }
    val taskMode = initialMode ?: harnessTaskMode
    val chatMode = taskMode == AutomationMode.CHAT
    val canCreateChatInteraction = harnessState.usageMode == LocalUsageMode.CHAT &&
        !harnessState.groupChat.enabled &&
        harnessState.sessionId.isNotBlank()
    val visibleTasks = state.tasks.filter { it.mode == taskMode }
    val createInvalidMessage = stringResource(R.string.tasks_create_invalid)
    var showCreate by rememberSaveable { mutableStateOf(false) }
    val editingTaskIdState = rememberSaveable { mutableStateOf<String?>(null) }
    var editingTaskId by editingTaskIdState
    val promptState = rememberSaveable { mutableStateOf("") }
    var prompt by promptState
    val cadenceState = rememberSaveable { mutableStateOf(AutomationCadence.ONCE) }
    var cadence by cadenceState
    val firstRunAtState = rememberSaveable { mutableStateOf(System.currentTimeMillis() + 60L * 60_000L) }
    var firstRunAt by firstRunAtState
    val customHoursState = rememberSaveable { mutableStateOf("6") }
    var customHours by customHoursState
    val windowStartMinuteOfDayState = rememberSaveable { mutableStateOf(20 * 60) }
    var windowStartMinuteOfDay by windowStartMinuteOfDayState
    val windowEndMinuteOfDayState = rememberSaveable { mutableStateOf(22 * 60) }
    var windowEndMinuteOfDay by windowEndMinuteOfDayState
    val quietHoursEnabledState = rememberSaveable { mutableStateOf(true) }
    var quietHoursEnabled by quietHoursEnabledState
    val quietStartMinuteOfDayState = rememberSaveable { mutableStateOf(23 * 60) }
    var quietStartMinuteOfDay by quietStartMinuteOfDayState
    val quietEndMinuteOfDayState = rememberSaveable { mutableStateOf(7 * 60) }
    var quietEndMinuteOfDay by quietEndMinuteOfDayState
    val proactiveMinGapHoursState = rememberSaveable { mutableStateOf(6) }
    var proactiveMinGapHours by proactiveMinGapHoursState
    val proactiveMaxUnansweredState = rememberSaveable { mutableStateOf(2) }
    var proactiveMaxUnanswered by proactiveMaxUnansweredState
    val createErrorState = rememberSaveable { mutableStateOf<String?>(null) }
    var createError by createErrorState
    val editorStateRefs = TaskEditorStateRefs(
        editingTaskId = editingTaskIdState,
        prompt = promptState,
        cadence = cadenceState,
        firstRunAt = firstRunAtState,
        customHours = customHoursState,
        windowStartMinuteOfDay = windowStartMinuteOfDayState,
        windowEndMinuteOfDay = windowEndMinuteOfDayState,
        quietHoursEnabled = quietHoursEnabledState,
        quietStartMinuteOfDay = quietStartMinuteOfDayState,
        quietEndMinuteOfDay = quietEndMinuteOfDayState,
        proactiveMinGapHours = proactiveMinGapHoursState,
        proactiveMaxUnanswered = proactiveMaxUnansweredState,
        createError = createErrorState,
    )
    val resetEditor = {
        showCreate = false
        editingTaskId = null
        prompt = ""
        cadence = AutomationCadence.ONCE
        firstRunAt = System.currentTimeMillis() + 60L * 60_000L
        customHours = "6"
        windowStartMinuteOfDay = 20 * 60
        windowEndMinuteOfDay = 22 * 60
        quietHoursEnabled = true
        quietStartMinuteOfDay = 23 * 60
        quietEndMinuteOfDay = 7 * 60
        proactiveMinGapHours = 6
        proactiveMaxUnanswered = 2
        createError = null
    }
    val navigateBack = {
        if (showCreate) resetEditor() else onClose()
    }
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
            Modifier.fillMaxSize().safeDrawingPadding()
                .padding(horizontal = DsSpacing.large, vertical = DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.large),
        ) {
            DsTopBar(
                title = stringResource(
                    if (chatMode) R.string.tasks_chat_title else R.string.tasks_title,
                ),
                subtitle = stringResource(
                    if (chatMode) R.string.tasks_chat_subtitle else R.string.tasks_subtitle,
                ),
                onBack = navigateBack,
                backContentDescription = stringResource(R.string.common_back),
                actionIcon = if (
                    showCreate || (chatMode && !canCreateChatInteraction)
                ) null else Icons.Filled.Add,
                actionContentDescription = stringResource(
                    if (chatMode) R.string.tasks_chat_new else R.string.tasks_new,
                ),
                onAction = {
                    showCreate = true
                    editingTaskId = null
                    prompt = ""
                    cadence = AutomationCadence.ONCE
                    firstRunAt = System.currentTimeMillis() + 60L * 60_000L
                    customHours = "6"
                    windowStartMinuteOfDay = 20 * 60
                    windowEndMinuteOfDay = 22 * 60
                    quietHoursEnabled = true
                    quietStartMinuteOfDay = 23 * 60
                    quietEndMinuteOfDay = 7 * 60
                    proactiveMinGapHours = 6
                    proactiveMaxUnanswered = 2
                    createError = null
                },
            )

            if (!showCreate && chatMode && !canCreateChatInteraction) {
                Text(
                    stringResource(R.string.tasks_chat_mode_unavailable),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.labelSecondary,
                )
            }

            if (showCreate) {
                TaskEditorPane(
                    state = editorStateRefs,
                    chatMode = chatMode,
                    taskMode = taskMode,
                    harnessState = harnessState,
                    viewModel = viewModel,
                    createInvalidMessage = createInvalidMessage,
                    onReset = resetEditor,
                )
            } else if (visibleTasks.isEmpty()) {
                EmptyHero(
                    headline = stringResource(R.string.tasks_empty_title),
                    subtitle = stringResource(R.string.tasks_empty_subtitle),
                )
            } else {
                LazyColumn(
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
                                editingTaskId = task.id
                                prompt = task.prompt
                                cadence = cadenceForTask(task)
                                firstRunAt = maxOf(
                                    task.nextRunAt,
                                    System.currentTimeMillis() + 60_000L,
                                )
                                customHours = customHoursForTask(task)
                                windowStartMinuteOfDay = task.windowStartMinuteOfDay ?: 20 * 60
                                windowEndMinuteOfDay = task.windowEndMinuteOfDay ?: 22 * 60
                                quietHoursEnabled = task.quietHoursEnabled
                                quietStartMinuteOfDay =
                                    task.quietStartHour * 60 + task.quietStartMinute
                                quietEndMinuteOfDay =
                                    task.quietEndHour * 60 + task.quietEndMinute
                                proactiveMinGapHours =
                                    maxOf(1, (task.proactiveMinGapMinutes / 60L).toInt())
                                proactiveMaxUnanswered = task.proactiveMaxUnanswered
                                createError = null
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


private fun cadenceForTask(task: AutomationTask): AutomationCadence =
    when (task.scheduleType) {
        AutomationScheduleType.ONCE -> AutomationCadence.ONCE
        AutomationScheduleType.DAILY -> AutomationCadence.DAILY
        AutomationScheduleType.WEEKLY -> AutomationCadence.WEEKLY
        AutomationScheduleType.INTERVAL -> AutomationCadence.CUSTOM
        AutomationScheduleType.SILENCE -> AutomationCadence.SILENCE
        AutomationScheduleType.WINDOW -> AutomationCadence.WINDOW
        AutomationScheduleType.LEGACY -> when (task.recurringMinutes) {
            null -> AutomationCadence.ONCE
            24L * 60L -> AutomationCadence.DAILY
            7L * 24L * 60L -> AutomationCadence.WEEKLY
            else -> AutomationCadence.CUSTOM
        }
    }

private fun customHoursForTask(task: AutomationTask): String {
    val minutes = if (task.scheduleType == AutomationScheduleType.SILENCE) {
        task.silenceMinutes
    } else {
        task.recurringMinutes
    } ?: return "6"
    return maxOf(1L, minutes / 60L).toString()
}

internal fun formatTime(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
