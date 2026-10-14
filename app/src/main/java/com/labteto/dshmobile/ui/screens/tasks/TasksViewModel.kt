package com.labteto.dshmobile.ui.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.automation.*
import com.labteto.dshmobile.uicontract.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class TasksNotice { CANCELLED, MISSING, RUN_STARTED, RUN_FAILED }
enum class PlannerUiError { SESSION_CHANGED, SAVE_FAILED, PLAN_FAILED }
internal enum class AutomationCadence { ONCE, DAILY, WEEKLY, MONTHLY, CUSTOM }
data class TasksUiState(
    val tasks: List<AutomationTask> = emptyList(), val notice: TasksNotice? = null,
    val planning: Boolean = false, val plannerError: PlannerUiError? = null, val saveRevision: Long = 0L,
)
@HiltViewModel
class TasksViewModel @Inject constructor(private val automation: AutomationUiPort) : ViewModel() {
    val harnessState = automation.snapshots.map { it.context }.distinctUntilChanged().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), automation.snapshot().context)
    private val _state = MutableStateFlow(TasksUiState(tasks = automation.snapshot().tasks))
    val state = _state.asStateFlow()
    private val planner = AutomationPlannerUiController(viewModelScope, automation, _state)
    init { viewModelScope.launch { automation.snapshots.collect { snapshot ->
        _state.update { it.copy(tasks = snapshot.tasks) }
    } } }
    fun acknowledgeNotice(notice: TasksNotice) {
        if (_state.value.notice == notice) _state.update { it.copy(notice = null) }
    }
    fun refresh() = _state.update { it.copy(tasks = automation.snapshot().tasks, notice = null) }
    private fun applied(command: AutomationUiCommand): Boolean {
        val result = automation.submit(command)
        if (result is AutomationUiReceipt.Applied) refresh()
        return result is AutomationUiReceipt.Applied
    }
    fun cancel(id: String) {
        val removed = applied(AutomationUiCommand.Delete(id))
        _state.update { it.copy(notice = if (removed) TasksNotice.CANCELLED else TasksNotice.MISSING) }
    }
    fun pause(id: String) { applied(AutomationUiCommand.Pause(id)) }
    fun resume(id: String) { applied(AutomationUiCommand.Resume(id)) }
    fun runNow(id: String): Boolean {
        val started = applied(AutomationUiCommand.RunNow(id))
        _state.update { it.copy(notice = if (started) TasksNotice.RUN_STARTED else TasksNotice.RUN_FAILED) }
        return started
    }
    fun submitChatPlan(input: String, editingTaskId: String? = null) = planner.submitChatPlan(input, editingTaskId)
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
        notify: Boolean? = null,
    ): Boolean {
        val task = _state.value.tasks.firstOrNull { it.id == id } ?: return false
        return applied(AutomationUiCommand.Update(id, task.scheduleGeneration, UiAutomationSchedule(
            prompt, firstRunAt, recurringMinutes, task.mode, scheduleType, silenceMinutes,
            windowStartMinuteOfDay, windowEndMinuteOfDay, quietHoursEnabled, quietStartHour, quietStartMinute,
            quietEndHour, quietEndMinute, proactiveMinGapMinutes, proactiveMaxUnanswered, notify ?: task.notify)))
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
        quietStartHour: Int = AutomationUiDefaults.DEFAULT_QUIET_START_HOUR,
        quietStartMinute: Int = AutomationUiDefaults.DEFAULT_QUIET_START_MINUTE,
        quietEndHour: Int = AutomationUiDefaults.DEFAULT_QUIET_END_HOUR,
        quietEndMinute: Int = AutomationUiDefaults.DEFAULT_QUIET_END_MINUTE,
        proactiveMinGapMinutes: Long = AutomationUiDefaults.DEFAULT_PROACTIVE_MIN_GAP_MINUTES,
        proactiveMaxUnanswered: Int = AutomationUiDefaults.DEFAULT_PROACTIVE_MAX_UNANSWERED,
        notify: Boolean = true,
    ): Boolean {
        return applied(AutomationUiCommand.Create(harnessState.value.identity, UiAutomationSchedule(
            prompt, firstRunAt, recurringMinutes, mode, scheduleType, silenceMinutes,
            windowStartMinuteOfDay, windowEndMinuteOfDay, quietHoursEnabled, quietStartHour, quietStartMinute,
            quietEndHour, quietEndMinute, proactiveMinGapMinutes, proactiveMaxUnanswered, notify)))
    }
    fun updateChatPolicy(
        task: AutomationTask,
        quietHoursEnabled: Boolean,
        quietStartHour: Int,
        quietStartMinute: Int,
        quietEndHour: Int,
        quietEndMinute: Int,
        proactiveMinGapMinutes: Long,
        proactiveMaxUnanswered: Int,
        notify: Boolean,
    ): Boolean = applied(AutomationUiCommand.UpdatePolicy(task.id, task.scheduleGeneration, UiAutomationInteractionPolicy(
        quietHoursEnabled, quietStartHour, quietStartMinute, quietEndHour, quietEndMinute,
        proactiveMinGapMinutes, proactiveMaxUnanswered, notify)))
}
