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
