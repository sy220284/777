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
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.automation.HarnessAutomationScheduler
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
) : ViewModel() {
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

    fun createAt(prompt: String, firstRunAt: Long, recurringMinutes: Long?): Boolean {
        if (prompt.isBlank() || firstRunAt <= System.currentTimeMillis()) return false
        if (recurringMinutes != null && recurringMinutes < 60L) return false
        return runCatching {
            val id = "ui-" + System.currentTimeMillis()
            if (recurringMinutes == null) {
                scheduler.scheduleOnce(id, prompt.trim(), firstRunAt, notify = true)
            } else {
                scheduler.schedulePeriodic(id, prompt.trim(), recurringMinutes, firstRunAt, notify = true)
            }
            refresh()
        }.isSuccess
    }
}

@Composable
fun TasksScreen(
    onClose: () -> Unit,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
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
                title = stringResource(R.string.tasks_title),
                subtitle = stringResource(R.string.tasks_subtitle),
                onBack = onClose,
                backContentDescription = stringResource(R.string.common_back),
                actionIcon = Icons.Filled.Add,
                actionContentDescription = stringResource(R.string.tasks_new),
                onAction = {
                    showCreate = !showCreate
                    createError = null
                },
            )

            if (showCreate) {
                DsGroupCard {
                    Text(stringResource(R.string.tasks_new), style = DsType.base16Strong, color = colors.labelPrimary)
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text(stringResource(R.string.tasks_prompt_label)) },
                        placeholder = { Text(stringResource(R.string.tasks_prompt_hint)) },
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
                                val ok = (cadence != AutomationCadence.CUSTOM || recurring != null) &&
                                    viewModel.createAt(prompt, firstRunAt, recurring)
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

            if (state.tasks.isEmpty() && !showCreate) {
                EmptyHero(
                    headline = stringResource(R.string.tasks_empty_title),
                    subtitle = stringResource(R.string.tasks_empty_subtitle),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    items(state.tasks, key = AutomationTask::id) { task ->
                        TaskCard(task = task, onCancel = { viewModel.cancel(task.id) })
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
private fun TaskCard(task: AutomationTask, onCancel: () -> Unit) {
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
                    task.prompt.lineSequence().firstOrNull()?.trim()?.take(56).orEmpty()
                        .ifBlank { stringResource(R.string.tasks_background_task) },
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
