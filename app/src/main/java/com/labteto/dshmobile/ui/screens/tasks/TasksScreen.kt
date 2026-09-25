package com.labteto.dshmobile.ui.screens.tasks

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat as AndroidDateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.automation.HarnessAutomationScheduler
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.rootSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TasksNotice { CANCELLED, MISSING }

private enum class AutomationCadence { ONCE, DAILY, WEEKLY, CUSTOM }

data class TasksUiState(
    val tasks: List<AutomationTask> = emptyList(),
    val notice: TasksNotice? = null,
)

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val scheduler: HarnessAutomationScheduler,
    private val engine: LocalHarnessEngine,
) : ViewModel() {
    val harnessState = engine.state
    private val _state = MutableStateFlow(TasksUiState())
    val state: StateFlow<TasksUiState> = _state.asStateFlow()

    init {
        refresh()
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

    fun createAt(
        prompt: String,
        firstRunAt: Long,
        recurringMinutes: Long?,
        mode: AutomationMode,
    ): Boolean {
        if (prompt.isBlank() || firstRunAt <= System.currentTimeMillis()) return false
        if (recurringMinutes != null && recurringMinutes < 60L) return false
        val snapshot = engine.state.value
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
            if (recurringMinutes == null) {
                scheduler.scheduleOnce(
                    id = id,
                    prompt = prompt.trim(),
                    triggerAtMillis = firstRunAt,
                    notify = true,
                    mode = mode,
                    targetSessionId = targetSessionId,
                    actorName = actorName,
                )
            } else {
                scheduler.schedulePeriodic(
                    id = id,
                    prompt = prompt.trim(),
                    intervalMinutes = recurringMinutes,
                    firstRunAtMillis = firstRunAt,
                    notify = true,
                    mode = mode,
                    targetSessionId = targetSessionId,
                    actorName = actorName,
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
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val harnessState by viewModel.harnessState.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    val taskMode = if (harnessState.usageMode == LocalUsageMode.CHAT) {
        AutomationMode.CHAT
    } else {
        AutomationMode.WORK
    }
    val chatMode = taskMode == AutomationMode.CHAT
    val visibleTasks = state.tasks.filter { it.mode == taskMode }
    val context = LocalContext.current
    val createInvalidMessage = stringResource(R.string.tasks_create_invalid)
    var showCreate by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var cadence by remember { mutableStateOf(AutomationCadence.ONCE) }
    var firstRunAt by remember { mutableStateOf(System.currentTimeMillis() + 60L * 60_000L) }
    var customHours by remember { mutableStateOf("6") }
    var createError by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)

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
                onBack = onClose,
                backContentDescription = stringResource(R.string.common_back),
                actionIcon = Icons.Filled.Add,
                actionContentDescription = stringResource(
                    if (chatMode) R.string.tasks_chat_new else R.string.tasks_new,
                ),
                onAction = {
                    showCreate = !showCreate
                    createError = null
                },
            )

            if (showCreate) {
                DsGroupCard {
                    Text(
                        stringResource(if (chatMode) R.string.tasks_chat_new else R.string.tasks_new),
                        style = DsType.base16Strong,
                        color = colors.labelPrimary,
                    )
                    if (chatMode) {
                        Text(
                            stringResource(
                                R.string.tasks_chat_target,
                                harnessState.chatPersona.name.ifBlank {
                                    stringResource(R.string.tasks_chat_character_fallback)
                                },
                            ),
                            style = DsType.small13Strong,
                            color = colors.labelSecondary,
                        )
                        if (harnessState.groupChat.enabled) {
                            Text(
                                stringResource(R.string.tasks_chat_group_unsupported),
                                style = DsType.small13,
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
                        style = DsType.small13Strong,
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
                    CadenceRow(
                        first = AutomationCadence.WEEKLY,
                        firstLabel = stringResource(R.string.tasks_schedule_weekly),
                        second = AutomationCadence.CUSTOM,
                        secondLabel = stringResource(R.string.tasks_schedule_custom),
                        selected = cadence,
                        onSelect = { cadence = it },
                    )

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

                    if (cadence == AutomationCadence.CUSTOM) {
                        OutlinedTextField(
                            value = customHours,
                            onValueChange = { customHours = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.tasks_custom_hours)) },
                            supportingText = { Text(stringResource(R.string.tasks_custom_hours_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    createError?.let {
                        Text(it, style = DsType.small13, color = colors.error)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        DsButton(
                            text = stringResource(R.string.common_cancel),
                            onClick = { showCreate = false; createError = null },
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                        DsButton(
                            text = stringResource(R.string.tasks_create),
                            onClick = {
                                val recurring = when (cadence) {
                                    AutomationCadence.ONCE -> null
                                    AutomationCadence.DAILY -> 24L * 60L
                                    AutomationCadence.WEEKLY -> 7L * 24L * 60L
                                    AutomationCadence.CUSTOM -> customHours.toLongOrNull()?.times(60L)
                                }
                                val ok = !(
                                    chatMode && harnessState.groupChat.enabled
                                ) &&
                                    (cadence != AutomationCadence.CUSTOM || recurring != null) &&
                                    viewModel.createAt(prompt, firstRunAt, recurring, taskMode)
                                if (ok) {
                                    showCreate = false
                                    prompt = ""
                                    cadence = AutomationCadence.ONCE
                                    firstRunAt = System.currentTimeMillis() + 60L * 60_000L
                                    customHours = "6"
                                    createError = null
                                } else {
                                    createError = createInvalidMessage
                                }
                            },
                            size = DsButtonSize.Small,
                        )
                    }
                }
            }

            if (visibleTasks.isEmpty() && !showCreate) {
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
                            onOpenSession = onOpenSession,
                        )
                    }
                }
            }

            state.notice?.let { notice ->
                Text(
                    stringResource(
                        when (notice) {
                            TasksNotice.CANCELLED -> R.string.tasks_cancelled
                            TasksNotice.MISSING -> R.string.tasks_missing
                        },
                    ),
                    style = DsType.small13,
                    color = colors.labelSecondary,
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
        style = DsType.small13Strong,
        color = DsTheme.colors.labelSecondary,
    )
    CadenceRow(
        first = AutomationCadence.ONCE,
        firstLabel = stringResource(R.string.tasks_chat_preset_morning),
        second = AutomationCadence.DAILY,
        secondLabel = stringResource(R.string.tasks_chat_preset_night),
        selected = AutomationCadence.CUSTOM,
        onSelect = { choice -> onSelect(if (choice == AutomationCadence.ONCE) morning else night) },
    )
    CadenceRow(
        first = AutomationCadence.ONCE,
        firstLabel = stringResource(R.string.tasks_chat_preset_reach_out),
        second = AutomationCadence.DAILY,
        secondLabel = stringResource(R.string.tasks_chat_preset_story),
        selected = AutomationCadence.CUSTOM,
        onSelect = { choice -> onSelect(if (choice == AutomationCadence.ONCE) reachOut else story) },
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
    onOpenSession: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val scheduleLabel = when (task.recurringMinutes) {
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            StateDot(taskStatus(task.status))
            Column(Modifier.weight(1f)) {
                Text(
                    if (task.mode == AutomationMode.CHAT) {
                        buildString {
                            append(task.actorName?.takeIf(String::isNotBlank)
                                ?: stringResource(R.string.tasks_chat_character_fallback))
                            append(" · ")
                            append(task.prompt.lineSequence().firstOrNull()?.trim()?.take(42).orEmpty())
                        }
                    } else {
                        task.prompt.lineSequence().firstOrNull()?.trim()?.take(56).orEmpty()
                            .ifBlank { stringResource(R.string.tasks_background_task) }
                    },
                    style = DsType.std14Strong,
                    color = colors.labelPrimary,
                )
                Text(scheduleLabel, style = DsType.caption11, color = colors.labelTertiary)
            }
            DsButton(
                text = stringResource(R.string.tasks_cancel),
                onClick = onCancel,
                size = DsButtonSize.Small,
                variant = DsButtonVariant.Ghost,
            )
        }
        Text(
            stringResource(R.string.tasks_next_run, formatTime(task.nextRunAt)),
            style = DsType.caption11,
            color = colors.labelTertiary,
            modifier = Modifier.padding(start = DsSpacing.xlarge),
        )
        task.lastError?.takeIf(String::isNotBlank)?.let {
            Text(
                stringResource(R.string.tasks_last_status, it.take(160)),
                style = DsType.caption11,
                color = colors.error,
                modifier = Modifier.padding(start = DsSpacing.xlarge),
            )
        }
        task.lastResult?.takeIf(String::isNotBlank)?.let {
            Text(
                stringResource(R.string.tasks_last_result, it.replace("\n", " ").take(180)),
                style = DsType.caption11,
                color = colors.labelSecondary,
                modifier = Modifier.padding(start = DsSpacing.xlarge),
                maxLines = 2,
            )
        }
        (task.targetSessionId ?: task.workSessionId)?.takeIf(String::isNotBlank)?.let { sessionId ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DsButton(
                    text = stringResource(R.string.tasks_open_result),
                    onClick = { onOpenSession(sessionId) },
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Outline,
                )
            }
        }
    }
}

private fun taskStatus(status: String): StateDotState = when (status) {
    "running", "queued" -> StateDotState.Running
    "completed", "scheduled" -> StateDotState.Done
    "blocked" -> StateDotState.Warning
    "failed" -> StateDotState.Error
    else -> StateDotState.Idle
}

private fun formatTime(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
