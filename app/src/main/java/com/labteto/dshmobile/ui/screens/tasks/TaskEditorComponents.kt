package com.labteto.dshmobile.ui.screens.tasks

import com.shenyan.designsystem.foundation.SyMetric


import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyTextField
import com.shenyan.designsystem.components.SySwitchControl
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.validAutomationCommandSchedule
import com.labteto.dshmobile.local.presentation.LocalAutomationPolicyProjection
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.shenyan.designsystem.components.SyGroupCard
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

internal data class TaskEditorStateRefs(
    val editingTaskId: MutableState<String?>,
    val prompt: MutableState<String>,
    val cadence: MutableState<AutomationCadence>,
    val firstRunAt: MutableState<Long>,
    val customHours: MutableState<String>,
    val notify: MutableState<Boolean>,
    val createError: MutableState<String?>,
)

@Composable
internal fun ColumnScope.TaskEditorPane(
    state: TaskEditorStateRefs,
    viewModel: TasksViewModel,
    createInvalidMessage: String,
    saving: Boolean,
    onReset: () -> Unit,
) {
    var editingTaskId by state.editingTaskId
    var prompt by state.prompt
    var cadence by state.cadence
    var firstRunAt by state.firstRunAt
    var customHours by state.customHours
    var notify by state.notify
    var createError by state.createError
    var showSchedulePicker by rememberSaveable { mutableStateOf(false) }
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current

    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        SyGroupCard {
            Text(
                stringResource(
                    if (editingTaskId != null) R.string.tasks_edit else R.string.tasks_new,
                ),
                style = DsType.base16Strong.withReadingWeight(),
                color = colors.text,
            )
            SyTextField(
                value = prompt,
                onValueChange = {
                    prompt = it
                    createError = null
                },
                label = { Text(stringResource(R.string.tasks_prompt_label)) },
                placeholder = { Text(stringResource(R.string.tasks_prompt_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
            )
        }
        // Screen 13: goal, schedule and execution notifications are separate
        // panels. Keep the actual task's prompt and schedule as the only data owners.
        SyGroupCard {
            Text(
                stringResource(R.string.tasks_schedule_label),
                style = DsType.small13Strong.withReadingWeight(),
                color = colors.textSecondary,
            )
            CadenceRow(
                first = AutomationCadence.ONCE,
                firstLabel = stringResource(R.string.tasks_schedule_once),
                second = AutomationCadence.DAILY,
                secondLabel = stringResource(R.string.tasks_schedule_daily),
                selected = cadence,
                onSelect = {
                    cadence = it
                    createError = null
                },
            )
            CadenceRow(
                first = AutomationCadence.WEEKLY,
                firstLabel = stringResource(R.string.tasks_schedule_weekly),
                second = AutomationCadence.MONTHLY,
                secondLabel = stringResource(R.string.tasks_schedule_monthly),
                selected = cadence,
                onSelect = {
                    cadence = it
                    createError = null
                },
            )
            SyButton(
                text = stringResource(
                    R.string.tasks_first_run_value,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(firstRunAt)),
                ),
                onClick = {
                    showSchedulePicker = true
                },
                variant = SyButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
            SyButton(
                text = stringResource(R.string.tasks_schedule_custom),
                onClick = { cadence = AutomationCadence.CUSTOM },
                variant = if (cadence == AutomationCadence.CUSTOM) SyButtonVariant.Outline else SyButtonVariant.Ghost,
            )
            if (cadence == AutomationCadence.CUSTOM) {
                SyTextField(
                    value = customHours,
                    onValueChange = {
                        customHours = it.filter(Char::isDigit).take(5)
                        createError = null
                    },
                    label = { Text(stringResource(R.string.tasks_custom_hours)) },
                    supportingText = { Text(stringResource(R.string.tasks_custom_hours_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
        SyGroupCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.tasks_notify_title),
                        style = DsType.small13Strong.withReadingWeight(),
                        color = colors.text,
                    )
                    Text(
                        stringResource(R.string.tasks_notify_hint),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.textTertiary,
                    )
                }
                SySwitchControl(
                    checked = notify,
                    onCheckedChange = { notify = it },
                )
            }
            createError?.let {
                Text(
                    it,
                    style = DsType.small13.withReadingWeight(),
                    color = colors.dangerInk,
                )
            }
        }
    }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                SyButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onReset,
                    enabled = !saving,
                    variant = SyButtonVariant.Info,
                    modifier = Modifier.weight(1f),
                    size = SyButtonSize.Large,

                )
                SyButton(
                    text = stringResource(
                        if (editingTaskId != null) R.string.tasks_save else R.string.tasks_create,
                    ),
                    onClick = {
                        val recurringMinutes = when (cadence) {
                            AutomationCadence.ONCE -> null
                            AutomationCadence.DAILY -> 24L * 60L
                            AutomationCadence.WEEKLY -> 7L * 24L * 60L
                            AutomationCadence.MONTHLY -> 30L * 24L * 60L
                            AutomationCadence.CUSTOM -> customHours.toLongOrNull()?.times(60L)
                        }
                        val scheduleType = when (cadence) {
                            AutomationCadence.ONCE -> AutomationScheduleType.ONCE
                            AutomationCadence.DAILY -> AutomationScheduleType.DAILY
                            AutomationCadence.WEEKLY -> AutomationScheduleType.WEEKLY
                            AutomationCadence.MONTHLY -> AutomationScheduleType.MONTHLY
                            AutomationCadence.CUSTOM -> AutomationScheduleType.INTERVAL
                        }
                        if (!validAutomationCommandSchedule(
                                AutomationMode.WORK, scheduleType, firstRunAt, recurringMinutes,
                                System.currentTimeMillis())) {
                            createError = createInvalidMessage
                        } else {
                            viewModel.saveManualWorkTask(
                                editingId = editingTaskId, prompt = prompt,
                                firstRunAt = firstRunAt, recurringMinutes = recurringMinutes,
                                scheduleType = scheduleType, notify = notify,
                            ) { ok ->
                                if (ok) onReset() else createError = createInvalidMessage
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    size = SyButtonSize.Large,
                    enabled = !saving,

                )
            }
    if (showSchedulePicker) {
        TaskSchedulePicker(
            initialMillis = firstRunAt,
            onPicked = { picked ->
                firstRunAt = picked
                createError = null
                showSchedulePicker = false
            },
            onDismiss = { showSchedulePicker = false },
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
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        SyButton(
            text = firstLabel,
            onClick = { onSelect(first) },
            modifier = Modifier.weight(1f),
            variant = if (selected == first) SyButtonVariant.Info else SyButtonVariant.Ghost,
            size = SyButtonSize.Small,
        )
        SyButton(
            text = secondLabel,
            onClick = { onSelect(second) },
            modifier = Modifier.weight(1f),
            variant = if (selected == second) SyButtonVariant.Info else SyButtonVariant.Ghost,
            size = SyButtonSize.Small,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskSchedulePicker(
    initialMillis: Long,
    onPicked: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember(initialMillis) {
        Calendar.getInstance().apply {
            timeInMillis = maxOf(initialMillis, System.currentTimeMillis() + 60_000L)
        }
    }
    // Material DatePicker uses UTC midnight; convert the user's local calendar date explicitly.
    val initialUtcDay = remember(initialMillis) {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(
                initial.get(Calendar.YEAR),
                initial.get(Calendar.MONTH),
                initial.get(Calendar.DAY_OF_MONTH),
            )
        }.timeInMillis
    }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = initialUtcDay)
    val timeState = rememberTimePickerState(
        initialHour = initial.get(Calendar.HOUR_OF_DAY),
        initialMinute = initial.get(Calendar.MINUTE),
        is24Hour = true,
    )
    var choosingTime by rememberSaveable { mutableStateOf(false) }
    val selectedMillis = dateState.selectedDateMillis?.let { utcDay ->
        val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = utcDay
        }
        Calendar.getInstance().apply {
            set(
                utcCalendar.get(Calendar.YEAR),
                utcCalendar.get(Calendar.MONTH),
                utcCalendar.get(Calendar.DAY_OF_MONTH),
                timeState.hour,
                timeState.minute,
            )
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val validTime = selectedMillis != null && selectedMillis >= System.currentTimeMillis() + 60_000L

    SyResponsiveSheet(
        title = stringResource(
            if (choosingTime) R.string.app_schedule_choose_time else R.string.app_schedule_choose_date,
        ),
        onDismiss = onDismiss,
        scrollable = true,
    ) {
        if (!choosingTime) {
            DatePicker(
                state = dateState,
                modifier = Modifier.fillMaxWidth(),
                title = null,
                headline = null,
                showModeToggle = false,
            )
            SyButton(
                text = stringResource(R.string.app_schedule_next),
                onClick = { choosingTime = true },
                enabled = dateState.selectedDateMillis != null,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                TimeInput(state = timeState)
            }
            if (!validTime) {
                Text(
                    stringResource(R.string.app_schedule_future_hint),
                    style = DsType.caption11.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.warningInk,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                SyButton(
                    text = stringResource(R.string.common_back),
                    onClick = { choosingTime = false },
                    variant = SyButtonVariant.Ghost,
                    modifier = Modifier.weight(1f),
                )
                SyButton(
                    text = stringResource(R.string.common_ok),
                    onClick = { selectedMillis?.let(onPicked) },
                    enabled = validTime,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}


internal fun formatMinuteOfDay(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutomationTimePickerSheet(
    initialMinuteOfDay: Int,
    onPicked: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val time = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = true,
    )
    SyResponsiveSheet(
        title = stringResource(R.string.tasks_schedule_label),
        onDismiss = onDismiss,
        scrollable = true,
        footer = {
            SyButton(
                text = stringResource(R.string.common_ok),
                onClick = { onPicked(time.hour * 60 + time.minute) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) { TimeInput(state = time) }
}
