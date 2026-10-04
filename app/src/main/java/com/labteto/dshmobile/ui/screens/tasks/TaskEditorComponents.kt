package com.labteto.dshmobile.ui.screens.tasks

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

internal data class TaskEditorStateRefs(
    val editingTaskId: MutableState<String?>,
    val prompt: MutableState<String>,
    val cadence: MutableState<AutomationCadence>,
    val firstRunAt: MutableState<Long>,
    val customHours: MutableState<String>,
    val createError: MutableState<String?>,
)

@Composable
internal fun ColumnScope.TaskEditorPane(
    state: TaskEditorStateRefs,
    viewModel: TasksViewModel,
    createInvalidMessage: String,
    onReset: () -> Unit,
) {
    var editingTaskId by state.editingTaskId
    var prompt by state.prompt
    var cadence by state.cadence
    var firstRunAt by state.firstRunAt
    var customHours by state.customHours
    var createError by state.createError
    val context = LocalContext.current
    val colors = DsTheme.colors

    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        DsGroupCard {
            Text(
                stringResource(
                    if (editingTaskId != null) R.string.tasks_edit else R.string.tasks_new,
                ),
                style = DsType.base16Strong.withReadingWeight(),
                color = colors.labelPrimary,
            )
            OutlinedTextField(
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
                onSelect = {
                    cadence = it
                    createError = null
                },
            )
            CadenceRow(
                first = AutomationCadence.WEEKLY,
                firstLabel = stringResource(R.string.tasks_schedule_weekly),
                second = AutomationCadence.CUSTOM,
                secondLabel = stringResource(R.string.tasks_schedule_custom),
                selected = cadence,
                onSelect = {
                    cadence = it
                    createError = null
                },
            )
            DsButton(
                text = stringResource(
                    R.string.tasks_first_run_value,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(firstRunAt)),
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
            createError?.let {
                Text(
                    it,
                    style = DsType.small13.withReadingWeight(),
                    color = colors.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onReset,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
                DsButton(
                    text = stringResource(
                        if (editingTaskId != null) R.string.tasks_save else R.string.tasks_create,
                    ),
                    onClick = {
                        val recurringMinutes = when (cadence) {
                            AutomationCadence.ONCE -> null
                            AutomationCadence.DAILY -> 24L * 60L
                            AutomationCadence.WEEKLY -> 7L * 24L * 60L
                            AutomationCadence.CUSTOM -> customHours.toLongOrNull()?.times(60L)
                        }
                        val scheduleType = when (cadence) {
                            AutomationCadence.ONCE -> AutomationScheduleType.ONCE
                            AutomationCadence.DAILY -> AutomationScheduleType.DAILY
                            AutomationCadence.WEEKLY -> AutomationScheduleType.WEEKLY
                            AutomationCadence.CUSTOM -> AutomationScheduleType.INTERVAL
                        }
                        val ok = editingTaskId?.let { id ->
                            viewModel.updateTask(
                                id = id,
                                prompt = prompt,
                                firstRunAt = firstRunAt,
                                recurringMinutes = recurringMinutes,
                                scheduleType = scheduleType,
                                silenceMinutes = null,
                                windowStartMinuteOfDay = null,
                                windowEndMinuteOfDay = null,
                                quietHoursEnabled = false,
                                quietStartHour = 23,
                                quietStartMinute = 0,
                                quietEndHour = 7,
                                quietEndMinute = 0,
                                proactiveMinGapMinutes = 6L * 60L,
                                proactiveMaxUnanswered = 2,
                            )
                        } ?: viewModel.createAt(
                            prompt = prompt,
                            firstRunAt = firstRunAt,
                            recurringMinutes = recurringMinutes,
                            mode = AutomationMode.WORK,
                            scheduleType = scheduleType,
                        )
                        if (ok) onReset() else createError = createInvalidMessage
                    },
                    size = DsButtonSize.Small,
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
            calendar.set(year, month, dayOfMonth)
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


internal fun formatMinuteOfDay(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
