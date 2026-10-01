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

internal data class TaskEditorStateRefs(
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
internal fun ColumnScope.TaskEditorPane(
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

internal fun formatMinuteOfDay(minuteOfDay: Int): String {
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
