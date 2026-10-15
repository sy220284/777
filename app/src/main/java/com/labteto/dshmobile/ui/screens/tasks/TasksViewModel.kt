package com.labteto.dshmobile.ui.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.automation.*
import com.labteto.dshmobile.uicontract.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

enum class TasksNotice { CANCELLED, MISSING, RUN_STARTED, RUN_FAILED, PAUSED, RESUMED, OPERATION_FAILED, OPERATION_REJECTED }
enum class PlannerUiError { SESSION_CHANGED, SAVE_FAILED, PLAN_FAILED }
internal enum class AutomationCadence { ONCE, DAILY, WEEKLY, MONTHLY, CUSTOM }
data class TasksUiState(
    val tasks: List<AutomationTask> = emptyList(), val notice: TasksNotice? = null,
    val planning: Boolean = false, val plannerError: PlannerUiError? = null, val saveRevision: Long = 0L,
    val busyIds: Set<String> = emptySet(), val loading: Boolean = false, val managementFailed: Boolean = false,
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
    fun refresh() {
        try { _state.update { it.copy(tasks = automation.snapshot().tasks, notice = null, managementFailed = false) } }
        catch (_: Exception) { _state.update { it.copy(managementFailed = true) } }
    }
    private fun applied(command: AutomationUiCommand): Boolean {
        val result = automation.submit(command)
        if (result is AutomationUiReceipt.Applied) refresh()
        return result is AutomationUiReceipt.Applied
    }
    private fun manage(id: String, command: AutomationUiCommand, success: TasksNotice) {
        if (id in _state.value.busyIds) return
        _state.update { it.copy(busyIds = it.busyIds + id, notice = null) }
        viewModelScope.launch {
            try {
                // Reuse the receipt/read-back path from #730; never replay an uncertain mutation.
                var receipt: AutomationUiReceipt? = null
                val checked = runTaskManagementOperation(
                    operation = {
                        automation.submit(command).also { receipt = it } is AutomationUiReceipt.Applied
                    },
                    readTasks = { automation.snapshot().tasks },
                )
                _state.update { old -> old.copy(
                    tasks = checked.tasks ?: old.tasks,
                    managementFailed = checked.failed || receipt is AutomationUiReceipt.Failed,
                    notice = when {
                        checked.failed || receipt is AutomationUiReceipt.Failed -> TasksNotice.OPERATION_FAILED
                        receipt is AutomationUiReceipt.Rejected -> TasksNotice.OPERATION_REJECTED
                        checked.accepted -> success
                        else -> TasksNotice.OPERATION_FAILED
                    },
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(managementFailed = true, notice = TasksNotice.OPERATION_FAILED) } }
            finally { _state.update { it.copy(busyIds = it.busyIds - id) } }
        }
    }
    fun cancel(id: String) = manage(id, AutomationUiCommand.Delete(id), TasksNotice.CANCELLED)
    fun pause(id: String) = manage(id, AutomationUiCommand.Pause(id), TasksNotice.PAUSED)
    fun resume(id: String) = manage(id, AutomationUiCommand.Resume(id), TasksNotice.RESUMED)
    fun runNow(id: String): Boolean {
        if (id in _state.value.busyIds) return false
        manage(id, AutomationUiCommand.RunNow(id), TasksNotice.RUN_STARTED)
        return true // Request queued, not a claim of execution completion.
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
