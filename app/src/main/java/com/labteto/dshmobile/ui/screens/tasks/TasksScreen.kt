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

private enum class AutomationCadence { ONCE, DAILY, WEEKLY, CUSTOM, SILENCE, WINDOW }

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


private data class TaskEditorStateRefs(
    val editingTaskId: MutableState<String?>,
    val prompt: MutableState<String>,
    val cadence: MutableState<AutomationCadence>,
    val firstRunAt: MutableState<Long>,
    val customHours: MutableState<String>,
    val windowStartMinuteOfDay: MutableState<Int>,
    val windowEndMinuteOfDay: MutableState<Int>,
    val quietHoursEnabled: MutableState<Boolean>,
    val quietStartMinuteOfDay: MutableState<Int>,
    val quietEndMinuteOfDay: MutableState<Int>,
    val proactiveMinGapHours: MutableState<Int>,
    val proactiveMaxUnanswered: MutableState<Int>,
    val createError: MutableState<String?>,
)

@Composable
private fun ColumnScope.TaskEditorPane(
    state: TaskEditorStateRefs,
    chatMode: Boolean,
    taskMode: AutomationMode,
    harnessState: LocalHarnessTaskState,
    viewModel: TasksViewModel,
    createInvalidMessage: String,
    onReset: () -> Unit,
) {
    var editingTaskId by state.editingTaskId
    var prompt by state.prompt
    var cadence by state.cadence
    var firstRunAt by state.firstRunAt
    var customHours by state.customHours
    var windowStartMinuteOfDay by state.windowStartMinuteOfDay
    var windowEndMinuteOfDay by state.windowEndMinuteOfDay
    var quietHoursEnabled by state.quietHoursEnabled
    var quietStartMinuteOfDay by state.quietStartMinuteOfDay
    var quietEndMinuteOfDay by state.quietEndMinuteOfDay
    var proactiveMinGapHours by state.proactiveMinGapHours
    var proactiveMaxUnanswered by state.proactiveMaxUnanswered
    var createError by state.createError
    val context = LocalContext.current
    val colors = DsTheme.colors
    val resetEditor = onReset

    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
    ) {
        DsGroupCard {
                    Text(
                        stringResource(
                            if (editingTaskId != null) R.string.tasks_edit
                            else if (chatMode) R.string.tasks_chat_new
                            else R.string.tasks_new,
                        ),
                        style = DsType.base16Strong.withReadingWeight(),
                        color = colors.labelPrimary,
                    )
                    if (chatMode) {
                        val characterName = harnessState.chatPersona.name.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.tasks_chat_character_fallback)
                        Text(
                            stringResource(R.string.tasks_chat_target, characterName),
                            style = DsType.small13Strong.withReadingWeight(),
                            color = colors.labelSecondary,
                        )
                        if (harnessState.groupChat.enabled) {
                            Text(
                                stringResource(R.string.tasks_chat_group_unsupported),
                                style = DsType.small13.withReadingWeight(),
                                color = colors.error,
                            )
                        } else {
                            ChatInteractionPresets(onSelect = { selected ->
                                prompt = selected
                                createError = null
                            })
                        }
                    }
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = {
                            Text(
                                stringResource(
                                    if (chatMode) R.string.tasks_chat_prompt_label
                                    else R.string.tasks_prompt_label,
                                ),
                            )
                        },
                        placeholder = {
                            Text(
                                stringResource(
                                    if (chatMode) R.string.tasks_chat_prompt_hint
                                    else R.string.tasks_prompt_hint,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                    )

                    Text(
                        stringResource(R.string.tasks_schedule_label),
                        style = DsType.small13Strong.withReadingWeight(),
                        color = colors.labelSecondary,
                    )
                    CadenceRow(
                        first = AutomationCadence.ONCE,
                        firstLabel = stringResource(R.string.tasks_schedule_once),
                        second = AutomationCadence.DAILY,
                        secondLabel = stringResource(R.string.tasks_schedule_daily),
                        selected = cadence,
                        onSelect = { cadence = it },
                    )
                    if (chatMode) {
                        CadenceRow(
                            first = AutomationCadence.WEEKLY,
                            firstLabel = stringResource(R.string.tasks_schedule_weekly),
                            second = AutomationCadence.SILENCE,
                            secondLabel = stringResource(R.string.tasks_schedule_silence),
                            selected = cadence,
                            onSelect = { cadence = it },
                        )
                        CadenceRow(
                            first = AutomationCadence.CUSTOM,
                            firstLabel = stringResource(R.string.tasks_schedule_custom),
                            second = AutomationCadence.WINDOW,
                            secondLabel = stringResource(R.string.tasks_schedule_window),
                            selected = cadence,
                            onSelect = { cadence = it },
                        )
                    } else {
                        CadenceRow(
                            first = AutomationCadence.WEEKLY,
                            firstLabel = stringResource(R.string.tasks_schedule_weekly),
                            second = AutomationCadence.CUSTOM,
                            secondLabel = stringResource(R.string.tasks_schedule_custom),
                            selected = cadence,
                            onSelect = { cadence = it },
                        )
                    }

                    if (
                        cadence != AutomationCadence.SILENCE &&
                        cadence != AutomationCadence.WINDOW
                    ) {
                        DsButton(
                            text = stringResource(
                                R.string.tasks_first_run_value,
                                DateFormat.getDateTimeInstance(
                                    DateFormat.MEDIUM,
                                    DateFormat.SHORT,
                                ).format(Date(firstRunAt)),
                            ),
                            onClick = {
                                showSchedulePicker(context, firstRunAt) { picked ->
                                    firstRunAt = picked
                                    createError = null
                                }
                            },
                            variant = DsButtonVariant.Outline,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (
                        cadence == AutomationCadence.CUSTOM ||
                        cadence == AutomationCadence.SILENCE
                    ) {
                        OutlinedTextField(
                            value = customHours,
                            onValueChange = { customHours = it.filter(Char::isDigit).take(5) },
                            label = {
                                Text(
                                    stringResource(
                                        if (cadence == AutomationCadence.SILENCE) {
                                            R.string.tasks_chat_silence_hours
                                        } else {
                                            R.string.tasks_custom_hours
                                        },
                                    ),
                                )
                            },
                            supportingText = {
                                Text(
                                    stringResource(
                                        if (cadence == AutomationCadence.SILENCE) {
                                            R.string.tasks_chat_silence_hours_hint
                                        } else {
                                            R.string.tasks_custom_hours_hint
                                        },
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    if (cadence == AutomationCadence.WINDOW) {
                        Text(
                            stringResource(R.string.tasks_chat_window_hint),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelSecondary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                        ) {
                            DsButton(
                                text = stringResource(
                                    R.string.tasks_chat_window_start_value,
                                    formatMinuteOfDay(windowStartMinuteOfDay),
                                ),
                                onClick = {
                                    showTimeOfDayPicker(
                                        context = context,
                                        initialMinuteOfDay = windowStartMinuteOfDay,
                                    ) { windowStartMinuteOfDay = it }
                                },
                                modifier = Modifier.weight(1f),
                                variant = DsButtonVariant.Outline,
                                size = DsButtonSize.Small,
                            )
                            DsButton(
                                text = stringResource(
                                    R.string.tasks_chat_window_end_value,
                                    formatMinuteOfDay(windowEndMinuteOfDay),
                                ),
                                onClick = {
                                    showTimeOfDayPicker(
                                        context = context,
                                        initialMinuteOfDay = windowEndMinuteOfDay,
                                    ) { windowEndMinuteOfDay = it }
                                },
                                modifier = Modifier.weight(1f),
                                variant = DsButtonVariant.Outline,
                                size = DsButtonSize.Small,
                            )
                        }
                    }

                    if (chatMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.tasks_chat_quiet_hours),
                                    style = DsType.small13Strong.withReadingWeight(),
                                    color = colors.labelPrimary,
                                )
                                Text(
                                    stringResource(R.string.tasks_chat_quiet_hours_hint),
                                    style = DsType.caption11.withReadingWeight(),
                                    color = colors.labelSecondary,
                                )
                            }
                            Switch(
                                checked = quietHoursEnabled,
                                onCheckedChange = { quietHoursEnabled = it },
                            )
                        }

                        if (quietHoursEnabled) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                            ) {
                                DsButton(
                                    text = stringResource(
                                        R.string.tasks_chat_quiet_start_value,
                                        formatMinuteOfDay(quietStartMinuteOfDay),
                                    ),
                                    onClick = {
                                        showTimeOfDayPicker(
                                            context = context,
                                            initialMinuteOfDay = quietStartMinuteOfDay,
                                        ) { quietStartMinuteOfDay = it }
                                    },
                                    modifier = Modifier.weight(1f),
                                    variant = DsButtonVariant.Outline,
                                    size = DsButtonSize.Small,
                                )
                                DsButton(
                                    text = stringResource(
                                        R.string.tasks_chat_quiet_end_value,
                                        formatMinuteOfDay(quietEndMinuteOfDay),
                                    ),
                                    onClick = {
                                        showTimeOfDayPicker(
                                            context = context,
                                            initialMinuteOfDay = quietEndMinuteOfDay,
                                        ) { quietEndMinuteOfDay = it }
                                    },
                                    modifier = Modifier.weight(1f),
                                    variant = DsButtonVariant.Outline,
                                    size = DsButtonSize.Small,
                                )
                            }
                        }

                        Text(
                            stringResource(R.string.tasks_chat_min_gap),
                            style = DsType.small13Strong.withReadingWeight(),
                            color = colors.labelSecondary,
                        )
                        ProactiveGapRow(
                            selectedHours = proactiveMinGapHours,
                            onSelect = { proactiveMinGapHours = it },
                        )
                        Text(
                            stringResource(R.string.tasks_chat_unanswered_limit),
                            style = DsType.small13Strong.withReadingWeight(),
                            color = colors.labelSecondary,
                        )
                        ProactiveUnansweredRow(
                            selected = proactiveMaxUnanswered,
                            onSelect = { proactiveMaxUnanswered = it },
                        )
                    }

                    createError?.let {
                        Text(it, style = DsType.small13.withReadingWeight(), color = colors.error)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        DsButton(
                            text = stringResource(R.string.common_cancel),
                            onClick = resetEditor,
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                        DsButton(
                            text = stringResource(
                                if (editingTaskId != null) R.string.tasks_save
                                else R.string.tasks_create,
                            ),
                            onClick = {
                                val customMinutes = customHours.toLongOrNull()?.times(60L)
                                val recurring = when (cadence) {
                                    AutomationCadence.ONCE -> null
                                    AutomationCadence.DAILY -> 24L * 60L
                                    AutomationCadence.WEEKLY -> 7L * 24L * 60L
                                    AutomationCadence.CUSTOM -> customMinutes
                                    AutomationCadence.SILENCE -> customMinutes
                                    AutomationCadence.WINDOW -> 24L * 60L
                                }
                                val scheduleType = when (cadence) {
                                    AutomationCadence.ONCE -> AutomationScheduleType.ONCE
                                    AutomationCadence.DAILY -> AutomationScheduleType.DAILY
                                    AutomationCadence.WEEKLY -> AutomationScheduleType.WEEKLY
                                    AutomationCadence.CUSTOM -> AutomationScheduleType.INTERVAL
                                    AutomationCadence.SILENCE -> AutomationScheduleType.SILENCE
                                    AutomationCadence.WINDOW -> AutomationScheduleType.WINDOW
                                }
                                val silenceMinutes = recurring.takeIf {
                                    cadence == AutomationCadence.SILENCE
                                }
                                val cadenceValid = when (cadence) {
                                    AutomationCadence.CUSTOM,
                                    AutomationCadence.SILENCE -> recurring != null
                                    AutomationCadence.WINDOW ->
                                        windowStartMinuteOfDay != windowEndMinuteOfDay
                                    else -> true
                                }
                                val commonValid = !(
                                    chatMode && harnessState.groupChat.enabled
                                ) && cadenceValid
                                val ok = if (!commonValid) {
                                    false
                                } else {
                                    editingTaskId?.let { id ->
                                        viewModel.updateTask(
                                            id = id,
                                            prompt = prompt,
                                            firstRunAt = firstRunAt,
                                            recurringMinutes = recurring,
                                            scheduleType = scheduleType,
                                            silenceMinutes = silenceMinutes,
                                            windowStartMinuteOfDay = windowStartMinuteOfDay.takeIf {
                                                cadence == AutomationCadence.WINDOW
                                            },
                                            windowEndMinuteOfDay = windowEndMinuteOfDay.takeIf {
                                                cadence == AutomationCadence.WINDOW
                                            },
                                            quietHoursEnabled = chatMode && quietHoursEnabled,
                                            quietStartHour = quietStartMinuteOfDay / 60,
                                            quietStartMinute = quietStartMinuteOfDay % 60,
                                            quietEndHour = quietEndMinuteOfDay / 60,
                                            quietEndMinute = quietEndMinuteOfDay % 60,
                                            proactiveMinGapMinutes = proactiveMinGapHours * 60L,
                                            proactiveMaxUnanswered = proactiveMaxUnanswered,
                                        )
                                    } ?: viewModel.createAt(
                                        prompt = prompt,
                                        firstRunAt = firstRunAt,
                                        recurringMinutes = recurring,
                                        mode = taskMode,
                                        scheduleType = scheduleType,
                                        silenceMinutes = silenceMinutes,
                                        windowStartMinuteOfDay = windowStartMinuteOfDay.takeIf {
                                            cadence == AutomationCadence.WINDOW
                                        },
                                        windowEndMinuteOfDay = windowEndMinuteOfDay.takeIf {
                                            cadence == AutomationCadence.WINDOW
                                        },
                                        quietHoursEnabled = chatMode && quietHoursEnabled,
                                        quietStartHour = quietStartMinuteOfDay / 60,
                                        quietStartMinute = quietStartMinuteOfDay % 60,
                                        quietEndHour = quietEndMinuteOfDay / 60,
                                        quietEndMinute = quietEndMinuteOfDay % 60,
                                        proactiveMinGapMinutes = proactiveMinGapHours * 60L,
                                        proactiveMaxUnanswered = proactiveMaxUnanswered,
                                    )
                                }
                                if (ok) {
                                    resetEditor()
                                } else {
                                    createError = createInvalidMessage
                                }
                            },
                            size = DsButtonSize.Small,
                        )
                    }
        }
    }
}

@Composable
private fun ChatInteractionPresets(
    onSelect: (String) -> Unit,
) {
    val morning = stringResource(R.string.tasks_chat_preset_morning_prompt)
    val night = stringResource(R.string.tasks_chat_preset_night_prompt)
    val reachOut = stringResource(R.string.tasks_chat_preset_reach_out_prompt)
    val story = stringResource(R.string.tasks_chat_preset_story_prompt)
    val promise = stringResource(R.string.tasks_chat_preset_promise_prompt)

    Text(
        stringResource(R.string.tasks_chat_presets),
        style = DsType.small13Strong.withReadingWeight(),
        color = DsTheme.colors.labelSecondary,
    )
    ChatPresetRow(
        firstLabel = stringResource(R.string.tasks_chat_preset_morning),
        firstPrompt = morning,
        secondLabel = stringResource(R.string.tasks_chat_preset_night),
        secondPrompt = night,
        onSelect = onSelect,
    )
    ChatPresetRow(
        firstLabel = stringResource(R.string.tasks_chat_preset_reach_out),
        firstPrompt = reachOut,
        secondLabel = stringResource(R.string.tasks_chat_preset_story),
        secondPrompt = story,
        onSelect = onSelect,
    )
    DsButton(
        text = stringResource(R.string.tasks_chat_preset_promise),
        onClick = { onSelect(promise) },
        variant = DsButtonVariant.Ghost,
        size = DsButtonSize.Small,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChatPresetRow(
    firstLabel: String,
    firstPrompt: String,
    secondLabel: String,
    secondPrompt: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        DsButton(
            text = firstLabel,
            onClick = { onSelect(firstPrompt) },
            modifier = Modifier.weight(1f),
            variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
        )
        DsButton(
            text = secondLabel,
            onClick = { onSelect(secondPrompt) },
            modifier = Modifier.weight(1f),
            variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
        )
    }
}

@Composable
private fun CadenceRow(
    first: AutomationCadence,
    firstLabel: String,
    second: AutomationCadence,
    secondLabel: String,
    selected: AutomationCadence,
    onSelect: (AutomationCadence) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        DsButton(
            text = firstLabel,
            onClick = { onSelect(first) },
            modifier = Modifier.weight(1f),
            variant = if (selected == first) DsButtonVariant.Info else DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
        )
        DsButton(
            text = secondLabel,
            onClick = { onSelect(second) },
            modifier = Modifier.weight(1f),
            variant = if (selected == second) DsButtonVariant.Info else DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
        )
    }
}

@Composable
private fun ProactiveGapRow(
    selectedHours: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        listOf(3, 6, 12, 24).forEach { hours ->
            DsButton(
                text = stringResource(R.string.tasks_chat_hours_value, hours),
                onClick = { onSelect(hours) },
                modifier = Modifier.weight(1f),
                variant = if (selectedHours == hours) DsButtonVariant.Info else DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        }
    }
}

@Composable
private fun ProactiveUnansweredRow(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        listOf(1, 2, 3).forEach { count ->
            DsButton(
                text = stringResource(R.string.tasks_chat_times_value, count),
                onClick = { onSelect(count) },
                modifier = Modifier.weight(1f),
                variant = if (selected == count) DsButtonVariant.Info else DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        }
    }
}

private fun showTimeOfDayPicker(
    context: Context,
    initialMinuteOfDay: Int,
    onPicked: (Int) -> Unit,
) {
    TimePickerDialog(
        context,
        { _, hourOfDay, minute -> onPicked(hourOfDay * 60 + minute) },
        initialMinuteOfDay / 60,
        initialMinuteOfDay % 60,
        AndroidDateFormat.is24HourFormat(context),
    ).show()
}

private fun formatMinuteOfDay(minuteOfDay: Int): String {
    val hour = (minuteOfDay / 60).toString().padStart(2, '0')
    val minute = (minuteOfDay % 60).toString().padStart(2, '0')
    return "$hour:$minute"
}

private fun showSchedulePicker(
    context: Context,
    initialMillis: Long,
    onPicked: (Long) -> Unit,
) {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = maxOf(initialMillis, System.currentTimeMillis() + 60_000L)
    }
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    calendar.set(Calendar.MINUTE, minute)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    onPicked(calendar.timeInMillis)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                AndroidDateFormat.is24HourFormat(context),
            ).show()
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH),
    ).show()
}

@Composable
private fun TaskCard(
    task: AutomationTask,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val colors = DsTheme.colors
    var confirmDelete by remember(task.id) { mutableStateOf(false) }
    val chatCharacterFallback = stringResource(R.string.tasks_chat_character_fallback)
    val backgroundTaskLabel = stringResource(R.string.tasks_background_task)
    val scheduleLabel = when (task.scheduleType) {
        AutomationScheduleType.SILENCE -> {
            val minutes = task.silenceMinutes ?: task.recurringMinutes ?: 60L
            stringResource(R.string.tasks_schedule_silence_value, minutes / 60L)
        }
        AutomationScheduleType.DAILY -> stringResource(R.string.tasks_schedule_daily)
        AutomationScheduleType.WINDOW -> stringResource(
            R.string.tasks_schedule_window_value,
            formatMinuteOfDay(task.windowStartMinuteOfDay ?: 20 * 60),
            formatMinuteOfDay(task.windowEndMinuteOfDay ?: 22 * 60),
        )
        AutomationScheduleType.WEEKLY -> stringResource(R.string.tasks_schedule_weekly)
        AutomationScheduleType.INTERVAL -> {
            val minutes = task.recurringMinutes ?: 0L
            if (minutes % 60L == 0L) {
                stringResource(R.string.tasks_every_hours, minutes / 60L)
            } else {
                stringResource(R.string.tasks_every_minutes, minutes)
            }
        }
        AutomationScheduleType.ONCE -> stringResource(R.string.tasks_once)
        AutomationScheduleType.LEGACY -> when (task.recurringMinutes) {
            null -> stringResource(R.string.tasks_once)
            24L * 60L -> stringResource(R.string.tasks_schedule_daily)
            7L * 24L * 60L -> stringResource(R.string.tasks_schedule_weekly)
            else -> {
                val minutes = task.recurringMinutes ?: 0L
                if (minutes % 60L == 0L) {
                    stringResource(R.string.tasks_every_hours, minutes / 60L)
                } else {
                    stringResource(R.string.tasks_every_minutes, minutes)
                }
            }
        }
    }
    val title = if (task.mode == AutomationMode.CHAT) {
        val actor = task.actorName?.takeIf(String::isNotBlank) ?: chatCharacterFallback
        "$actor · ${task.prompt.lineSequence().firstOrNull()?.trim()?.take(42).orEmpty()}"
    } else {
        task.prompt.lineSequence().firstOrNull()?.trim()?.take(56).orEmpty()
            .ifBlank { backgroundTaskLabel }
    }
    val terminalOneShot = task.recurringMinutes == null &&
        task.status in setOf("completed", "failed", "blocked")
    val timing = when {
        task.status == "waiting_user" ->
            stringResource(R.string.tasks_waiting_user_timing)
        terminalOneShot && task.lastRunAt != null ->
            stringResource(R.string.tasks_last_run, formatTime(task.lastRunAt))
        else ->
            stringResource(R.string.tasks_next_run, formatTime(task.nextRunAt))
    }

    DsGroupCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                )
                Text(
                    timing,
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )
            }
            DsButton(
                text = stringResource(R.string.tasks_cancel),
                onClick = { confirmDelete = true },
                size = DsButtonSize.Small,
                variant = DsButtonVariant.Ghost,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsStatusPill(
                state = taskStatus(task.status),
                label = taskStatusLabel(task.status),
            )
            DsPill(text = scheduleLabel)
            if (task.mode == AutomationMode.CHAT && task.quietHoursEnabled) {
                DsPill(
                    text = stringResource(
                        R.string.tasks_chat_quiet_hours_value,
                        formatMinuteOfDay(task.quietStartHour * 60 + task.quietStartMinute),
                        formatMinuteOfDay(task.quietEndHour * 60 + task.quietEndMinute),
                    ),
                )
            }
        }

        task.lastError?.takeIf(String::isNotBlank)?.let {
            Text(
                stringResource(R.string.tasks_last_status, it.take(160)),
                style = DsType.caption11.withReadingWeight(),
                color = colors.error,
            )
        }
        task.lastResult?.takeIf(String::isNotBlank)?.let {
            Text(
                stringResource(R.string.tasks_last_result, it.replace("\n", " ").take(180)),
                style = DsType.caption11.withReadingWeight(),
                color = colors.labelSecondary,
                maxLines = 2,
            )
        }

        if (task.runReceipts.isNotEmpty()) {
            Text(
                stringResource(R.string.tasks_run_history, task.runReceipts.size),
                style = DsType.caption11.withReadingWeight(),
                color = colors.labelTertiary,
            )
            val timelineItems = mutableListOf<DsTimelineItem>()
            for (receipt in task.runReceipts.takeLast(3).asReversed()) {
                timelineItems += DsTimelineItem(
                    text = stringResource(
                        R.string.tasks_run_history_item,
                        formatTime(receipt.finishedAt),
                        receiptStatusLabel(receipt),
                    ),
                    state = receiptStatus(receipt.status),
                )
            }
            DsTimeline(items = timelineItems, modifier = Modifier.fillMaxWidth())
        }

        if (task.mode == AutomationMode.CHAT) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DsButton(
                    text = stringResource(R.string.tasks_try_now),
                    onClick = onRunNow,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Ghost,
                )
                DsButton(
                    text = stringResource(R.string.tasks_edit),
                    onClick = onEdit,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Ghost,
                )
            }
        }

        (task.targetSessionId ?: task.workSessionId)?.takeIf(String::isNotBlank)?.let { sessionId ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (task.mode == AutomationMode.CHAT && task.recurringMinutes != null) {
                    DsButton(
                        text = stringResource(
                            if (task.status == "paused") R.string.tasks_resume
                            else R.string.tasks_pause,
                        ),
                        onClick = if (task.status == "paused") onResume else onPause,
                        size = DsButtonSize.Small,
                        variant = DsButtonVariant.Ghost,
                    )
                }
                DsButton(
                    text = stringResource(R.string.tasks_open_result),
                    onClick = { onOpenSession(sessionId) },
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Outline,
                )
            }
        }
    }

    if (confirmDelete) {
        DsDialog(
            title = stringResource(R.string.tasks_delete_confirm_title),
            onDismiss = { confirmDelete = false },
        ) {
            Text(
                stringResource(
                    if (task.mode == AutomationMode.CHAT) R.string.tasks_chat_delete_confirm_body
                    else R.string.tasks_delete_confirm_body,
                ),
                style = DsType.std14.withReadingWeight(),
                color = colors.labelSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { confirmDelete = false },
                    variant = DsButtonVariant.Ghost,
                )
                DsButton(
                    text = stringResource(R.string.tasks_delete_confirm),
                    onClick = {
                        confirmDelete = false
                        onCancel()
                    },
                    variant = DsButtonVariant.Danger,
                )
            }
        }
    }
}

@Composable
private fun receiptStatusLabel(receipt: AutomationRunReceipt): String {
    val state = when (receipt.status) {
        "completed" -> stringResource(R.string.tasks_run_completed)
        "blocked" -> stringResource(R.string.tasks_run_blocked)
        "failed" -> stringResource(R.string.tasks_run_failed)
        "skipped" -> stringResource(R.string.tasks_run_skipped)
        else -> receipt.status
    }
    val detail = receipt.errorPreview ?: receipt.resultPreview
    return if (detail.isNullOrBlank()) state else "$state · $detail"
}

private fun receiptStatus(status: String): DsStatus = when (status) {
    "completed" -> DsStatus.Done
    "blocked" -> DsStatus.Warning
    "failed" -> DsStatus.Failed
    "running", "queued" -> DsStatus.Running
    "skipped" -> DsStatus.Neutral
    else -> DsStatus.Neutral
}

private fun taskStatus(status: String): DsStatus = when (status) {
    "running", "queued" -> DsStatus.Running
    "completed" -> DsStatus.Done
    "blocked" -> DsStatus.Warning
    "failed" -> DsStatus.Failed
    "paused", "waiting_user" -> DsStatus.Neutral
    else -> DsStatus.Neutral
}

@Composable
private fun taskStatusLabel(status: String): String = when (status) {
    "running" -> stringResource(R.string.tasks_status_running)
    "queued" -> stringResource(R.string.tasks_status_queued)
    "scheduled" -> stringResource(R.string.tasks_status_scheduled)
    "completed" -> stringResource(R.string.tasks_run_completed)
    "blocked" -> stringResource(R.string.tasks_run_blocked)
    "failed" -> stringResource(R.string.tasks_run_failed)
    "paused" -> stringResource(R.string.tasks_status_paused)
    "waiting_user" -> stringResource(R.string.tasks_status_waiting_user)
    else -> stringResource(R.string.tasks_status_scheduled)
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

private fun formatTime(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
