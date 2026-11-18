package com.labteto.dshmobile.ui.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.local.presentation.LocalHarnessTaskState
import com.labteto.dshmobile.local.presentation.LocalTaskRuntime
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.uicontract.AutomationUiCommand
import com.labteto.dshmobile.uicontract.AutomationUiPort
import com.labteto.dshmobile.uicontract.AutomationUiReceipt
import com.labteto.dshmobile.uicontract.UiAutomationInteractionPolicy
import com.labteto.dshmobile.uicontract.UiAutomationSchedule
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TasksNotice { CANCELLED, MISSING, RUN_STARTED, RUN_FAILED, PAUSED, RESUMED, OPERATION_FAILED, OPERATION_REJECTED }
enum class TaskManagementAction(val success: TasksNotice) {
    DELETE(TasksNotice.CANCELLED),
    PAUSE(TasksNotice.PAUSED),
    RESUME(TasksNotice.RESUMED),
    RUN_NOW(TasksNotice.RUN_STARTED),
    APPROVE(TasksNotice.RUN_STARTED),
}
enum class PlannerUiError { SESSION_CHANGED, SAVE_FAILED, PLAN_FAILED }
internal enum class AutomationCadence { ONCE, DAILY, WEEKLY, MONTHLY, CUSTOM }

data class TasksUiState(
    val tasks: List<AutomationTask> = emptyList(),
    val notice: TasksNotice? = null,
    val planning: Boolean = false,
    val plannerError: PlannerUiError? = null,
    val plannerFailureReason: String? = null,
    val saveRevision: Long = 0L,
    val busyIds: Set<String> = emptySet(),
    val loading: Boolean = false,
    val listLoadFailed: Boolean = false,
    val failedAction: TaskManagementAction? = null,
    val managementFailed: Boolean = false,
    val managementError: String? = null,
    val savingEditor: Boolean = false,
)

/** UI projection and interaction state; AutomationUiPort owns all task validation and writes. */
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val appServer: com.labteto.dshmobile.uicontract.AppServerUiPort,
    localRuntime: LocalTaskRuntime,
) : ViewModel() {
    private val automation: AutomationUiPort get() = appServer.automation
    val harnessState: StateFlow<LocalHarnessTaskState> = localRuntime.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = localRuntime.initialState,
    )
    private val mutableState = MutableStateFlow(TasksUiState(loading = true))
    val state: StateFlow<TasksUiState> = mutableState.asStateFlow()
    private var observation: Job? = null
    private val planner = AutomationPlannerUiController(viewModelScope, automation, mutableState)

    init { refresh() }

    fun acknowledgeNotice(notice: TasksNotice) {
        if (mutableState.value.notice == notice) mutableState.update { it.copy(notice = null) }
    }

    fun refresh() = viewModelScope.launch {
        mutableState.update { it.copy(loading = true) }
        try {
            val snapshot = withContext(Dispatchers.IO) { automation.snapshot() }
            mutableState.update { it.copy(tasks = snapshot.tasks, loading = false, listLoadFailed = false) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.failure("TaskScheduling", "refresh_tasks", failure)
            mutableState.update { it.copy(loading = false, listLoadFailed = true) }
        }
        observeTasks()
    }

    fun cancel(id: String) = manage(id, TaskManagementAction.DELETE) { AutomationUiCommand.Delete(id) }
    fun pause(id: String) = manage(id, TaskManagementAction.PAUSE) { AutomationUiCommand.Pause(id) }
    fun resume(id: String) = manage(id, TaskManagementAction.RESUME) { AutomationUiCommand.Resume(id) }
    fun runNow(id: String) = manage(id, TaskManagementAction.RUN_NOW) { AutomationUiCommand.RunNow(id) }
    fun approveBlocked(id: String) = manage(id, TaskManagementAction.APPROVE) {
        val generation = mutableState.value.tasks.firstOrNull { it.id == id }
            ?.scheduleGeneration ?: -1L
        AutomationUiCommand.ApproveBlocked(id, generation)
    }

    private fun manage(id: String, action: TaskManagementAction, command: () -> AutomationUiCommand) {
        if (id in mutableState.value.busyIds) return
        mutableState.update { it.copy(busyIds = it.busyIds + id, notice = null, managementFailed = false, managementError = null, failedAction = null) }
        viewModelScope.launch {
            try {
                val result = runTaskManagementOperation({ automation.submit(command()) }, {
                    automation.snapshot().tasks
                })
                mutableState.update { it.copy(
                    tasks = result.tasks ?: it.tasks,
                    managementFailed = result.failed,
                    managementError = result.failureMessage,
                    failedAction = action.takeIf { result.failed || !result.accepted },
                    notice = when {
                        result.accepted -> action.success
                        result.failed -> TasksNotice.OPERATION_FAILED
                        else -> TasksNotice.OPERATION_REJECTED
                    },
                ) }
            } finally {
                mutableState.update { it.copy(busyIds = it.busyIds - id) }
            }
        }
    }

    private fun observeTasks() {
        if (observation?.isActive == true) return
        observation = viewModelScope.launch {
            try {
                automation.snapshots.collect { snapshot ->
                    mutableState.update { it.copy(tasks = snapshot.tasks) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.failure("TaskScheduling", "observe_tasks", failure)
                mutableState.update { it.copy(listLoadFailed = true, loading = false) }
            }
        }
    }

    fun submitChatPlan(input: String, editingTaskId: String? = null) =
        planner.submitChatPlan(input, editingTaskId)

    /** Work editor sends its complete schedule through the same Feature boundary as Chat. */
    fun saveManualWorkTask(
        editingId: String?,
        prompt: String,
        firstRunAt: Long,
        recurringMinutes: Long?,
        scheduleType: AutomationScheduleType,
        notify: Boolean,
        onComplete: (Boolean) -> Unit,
    ) {
        if (mutableState.value.savingEditor) return
        val expectedGeneration = editingId?.let { id ->
            mutableState.value.tasks.firstOrNull { it.id == id && it.mode == AutomationMode.WORK }
                ?.scheduleGeneration
        }
        mutableState.update { it.copy(savingEditor = true, managementFailed = false, managementError = null, failedAction = null) }
        viewModelScope.launch {
            try {
                val result = runTaskManagementOperation(
                    operation = {
                        val schedule = UiAutomationSchedule(
                            prompt = prompt,
                            firstRunAt = firstRunAt,
                            recurringMinutes = recurringMinutes,
                            mode = AutomationMode.WORK,
                            scheduleType = scheduleType,
                            notify = notify,
                        )
                        when {
                            editingId == null -> automation.submit(AutomationUiCommand.Create(null, schedule))
                            expectedGeneration == null -> AutomationUiReceipt.Rejected(
                                com.labteto.dshmobile.uicontract.UiAutomationRejection.STALE_TASK)
                            else -> automation.submit(AutomationUiCommand.Update(
                                editingId, expectedGeneration, schedule))
                        }
                    },
                    readTasks = { automation.snapshot().tasks },
                )
                mutableState.update { it.copy(
                    tasks = result.tasks ?: it.tasks,
                    managementFailed = result.failed,
                    managementError = result.failureMessage,
                ) }
                onComplete(result.accepted)
            } finally {
                mutableState.update { it.copy(savingEditor = false) }
            }
        }
    }

    fun saveChatPolicyAsync(
        task: AutomationTask,
        quietHoursEnabled: Boolean,
        quietStartHour: Int,
        quietStartMinute: Int,
        quietEndHour: Int,
        quietEndMinute: Int,
        proactiveMinGapMinutes: Long,
        proactiveMaxUnanswered: Int,
        notify: Boolean,
        onComplete: (Boolean) -> Unit,
    ) {
        if (task.id in mutableState.value.busyIds) return
        mutableState.update { it.copy(busyIds = it.busyIds + task.id, managementFailed = false, managementError = null, failedAction = null) }
        viewModelScope.launch {
            try {
                val result = runTaskManagementOperation(
                    operation = {
                        automation.submit(AutomationUiCommand.UpdatePolicy(
                            task.id,
                            task.scheduleGeneration,
                            UiAutomationInteractionPolicy(
                                quietHoursEnabled, quietStartHour, quietStartMinute,
                                quietEndHour, quietEndMinute,
                                proactiveMinGapMinutes, proactiveMaxUnanswered, notify,
                            ),
                        ))
                    },
                    readTasks = { automation.snapshot().tasks },
                )
                mutableState.update { it.copy(
                    tasks = result.tasks ?: it.tasks,
                    managementFailed = result.failed,
                    managementError = result.failureMessage,
                ) }
                onComplete(result.accepted)
            } finally {
                mutableState.update { it.copy(busyIds = it.busyIds - task.id) }
            }
        }
    }
}
