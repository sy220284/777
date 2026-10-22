package com.labteto.dshmobile.ui.screens.tasks
import androidx.lifecycle.ViewModel
import com.labteto.dshmobile.observability.AppLog
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.automation.HarnessAutomationScheduler
import com.labteto.dshmobile.automation.validAutomationCommandSchedule
import com.labteto.dshmobile.automation.newAutomationTaskId
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.automation.AutomationPlanDraft
import com.labteto.dshmobile.local.presentation.LocalHarnessTaskState
import com.labteto.dshmobile.local.presentation.LocalAutomationPlanningFacade
import com.labteto.dshmobile.local.presentation.LocalAutomationPolicyProjection
import com.labteto.dshmobile.local.presentation.LocalTaskRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
enum class TasksNotice { CANCELLED, MISSING, RUN_STARTED, RUN_FAILED, PAUSED, RESUMED, OPERATION_FAILED, OPERATION_REJECTED }
enum class PlannerUiError { SESSION_CHANGED, SAVE_FAILED, PLAN_FAILED }
internal enum class AutomationCadence { ONCE, DAILY, WEEKLY, MONTHLY, CUSTOM }
data class TasksUiState(
    val tasks: List<AutomationTask> = emptyList(),
    val notice: TasksNotice? = null,
    val planning: Boolean = false,
    val plannerError: PlannerUiError? = null,
    val saveRevision: Long = 0L,
    val busyIds: Set<String> = emptySet(),
    val loading: Boolean = false,
    val managementFailed: Boolean = false,
    val savingEditor: Boolean = false,
)
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val scheduler: HarnessAutomationScheduler,
    private val localRuntime: LocalTaskRuntime,
    private val planningService: LocalAutomationPlanningFacade,
) : ViewModel() {
    val harnessState: StateFlow<LocalHarnessTaskState> = localRuntime.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = localRuntime.initialState,
    )
    private val _state = MutableStateFlow(TasksUiState(loading = true))
    val state: StateFlow<TasksUiState> = _state.asStateFlow()
    fun acknowledgeNotice(notice: TasksNotice) {
        if (_state.value.notice == notice) _state.update { it.copy(notice = null) }
    }
    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = true) }
        val result = runTaskManagementOperation({ true }, scheduler::list)
        _state.update { it.copy(tasks = observedTasks?.value ?: result.tasks ?: it.tasks, loading = false,
            managementFailed = result.failed) }
        observeTasks()
    }
    fun cancel(id: String) = manage(id, TasksNotice.CANCELLED) { scheduler.cancelTask(id) }
    fun pause(id: String) = manage(id, TasksNotice.PAUSED) { scheduler.pauseTask(id) }
    fun resume(id: String) = manage(id, TasksNotice.RESUMED) { scheduler.resumeTask(id) }
    fun runNow(id: String) = manage(id, TasksNotice.RUN_STARTED) { scheduler.runTaskNow(id) }

    private fun manage(id: String, success: TasksNotice, operation: () -> Boolean) {
        if (id in _state.value.busyIds) return
        _state.update { it.copy(busyIds = it.busyIds + id, notice = null) }
        viewModelScope.launch {
            try {
                val result = runTaskManagementOperation(operation, scheduler::list)
                _state.update { it.copy(tasks = observedTasks?.value ?: result.tasks ?: it.tasks,
                    managementFailed = result.failed,
                    notice = when {
                        result.failed -> TasksNotice.OPERATION_FAILED
                        !result.accepted -> TasksNotice.OPERATION_REJECTED
                        else -> success
                    }) }
            } finally { _state.update { it.copy(busyIds = it.busyIds - id) } }
        }
    }
    private var observation: kotlinx.coroutines.Job? = null
    private var observedTasks: StateFlow<List<AutomationTask>>? = null
    private fun observeTasks() {
        if (observation?.isActive == true) return
        observation = viewModelScope.launch {
            try {
                val tasks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { scheduler.tasks }
                observedTasks = tasks
                tasks.collect { current -> _state.update { it.copy(tasks = current) } }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.failure("TaskScheduling", "observe_tasks", error)
                _state.update { it.copy(managementFailed = true, loading = false) }
            }
        }
    }
    private val planner = AutomationPlannerUiController(
        viewModelScope, localRuntime, planningService, _state, ::saveChatDraft, scheduler::list,
    )
    init {
        refresh()
    }
    fun submitChatPlan(input: String, editingTaskId: String? = null) = planner.submitChatPlan(input, editingTaskId)
    private fun saveChatDraft(
        draft: AutomationPlanDraft,
        editingTaskId: String?,
        sessionId: String,
    ): Boolean {
        if (editingTaskId == null) {
            return createAt(
                prompt = draft.prompt,
                firstRunAt = draft.firstRunAt,
                recurringMinutes = draft.recurringMinutes,
                mode = AutomationMode.CHAT,
                scheduleType = draft.scheduleType,
                silenceMinutes = draft.silenceMinutes,
                windowStartMinuteOfDay = draft.windowStartMinuteOfDay,
                windowEndMinuteOfDay = draft.windowEndMinuteOfDay,
                quietHoursEnabled = true,
            )
        }
        val existing = scheduler.list().firstOrNull {
            it.id == editingTaskId &&
                it.mode == AutomationMode.CHAT &&
                it.targetSessionId == sessionId
        } ?: return false
        return updateTask(
            id = existing.id,
            prompt = draft.prompt,
            firstRunAt = draft.firstRunAt,
            recurringMinutes = draft.recurringMinutes,
            scheduleType = draft.scheduleType,
            silenceMinutes = draft.silenceMinutes,
            windowStartMinuteOfDay = draft.windowStartMinuteOfDay,
            windowEndMinuteOfDay = draft.windowEndMinuteOfDay,
            quietHoursEnabled = existing.quietHoursEnabled,
            quietStartHour = existing.quietStartHour,
            quietStartMinute = existing.quietStartMinute,
            quietEndHour = existing.quietEndHour,
            quietEndMinute = existing.quietEndMinute,
            proactiveMinGapMinutes = existing.proactiveMinGapMinutes,
            proactiveMaxUnanswered = existing.proactiveMaxUnanswered,
        )
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
        notify: Boolean? = null,
    ): Boolean = runCatching {
        require(validAutomationCommandSchedule(
            AutomationMode.CHAT, scheduleType, firstRunAt, recurringMinutes,
            System.currentTimeMillis(), silenceMinutes, windowStartMinuteOfDay, windowEndMinuteOfDay,
        )) { "任务排程参数无效" }
        scheduler.updateTask(
            id, prompt, firstRunAt, recurringMinutes, scheduleType, silenceMinutes,
            windowStartMinuteOfDay, windowEndMinuteOfDay, quietHoursEnabled,
            quietStartHour, quietStartMinute, quietEndHour, quietEndMinute,
            proactiveMinGapMinutes, proactiveMaxUnanswered, notify,
        )
    }.onFailure { AppLog.failure("TaskScheduling", "update_scheduled_task", it) }
        .getOrDefault(false).also { if (it) refresh() }
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
        quietStartHour: Int = LocalAutomationPolicyProjection.defaults.quietStartHour,
        quietStartMinute: Int = LocalAutomationPolicyProjection.defaults.quietStartMinute,
        quietEndHour: Int = LocalAutomationPolicyProjection.defaults.quietEndHour,
        quietEndMinute: Int = LocalAutomationPolicyProjection.defaults.quietEndMinute,
        proactiveMinGapMinutes: Long = LocalAutomationPolicyProjection.defaults.proactiveMinGapMinutes,
        proactiveMaxUnanswered: Int = LocalAutomationPolicyProjection.defaults.proactiveMaxUnanswered,
        notify: Boolean = true,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (prompt.isBlank() || !validAutomationCommandSchedule(
            mode, scheduleType, firstRunAt, recurringMinutes, now,
            silenceMinutes, windowStartMinuteOfDay, windowEndMinuteOfDay,
        )) return false
        if (scheduleType !in setOf(AutomationScheduleType.SILENCE, AutomationScheduleType.WINDOW) &&
            firstRunAt <= now
        ) return false
        val minimumRecurringMinutes = if (mode == AutomationMode.CHAT) 60L else 15L
        if (recurringMinutes != null && recurringMinutes < minimumRecurringMinutes) return false
        if (scheduleType == AutomationScheduleType.SILENCE &&
            (silenceMinutes == null || silenceMinutes < LocalAutomationPolicyProjection.minimumSilenceMinutes)
        ) return false
        if (scheduleType == AutomationScheduleType.WINDOW &&
            (windowStartMinuteOfDay == null || windowEndMinuteOfDay == null ||
                windowStartMinuteOfDay !in 0 until 24 * 60 ||
                windowEndMinuteOfDay !in 0 until 24 * 60 ||
                windowStartMinuteOfDay == windowEndMinuteOfDay)
        ) return false
        val snapshot = localRuntime.snapshot()
        if (mode == AutomationMode.CHAT && !canPlanChat(snapshot)) return false
        return runCatching {
            val id = newAutomationTaskId()
            val targetSessionId = snapshot.sessionId.takeIf { mode == AutomationMode.CHAT }
            val actorName = snapshot.chatPersona.name.takeIf {
                mode == AutomationMode.CHAT && it.isNotBlank()
            }
            when {
                scheduleType == AutomationScheduleType.WINDOW -> scheduler.scheduleWindow(
                    id, prompt.trim(), requireNotNull(windowStartMinuteOfDay),
                    requireNotNull(windowEndMinuteOfDay), notify, requireNotNull(targetSessionId),
                    actorName, quietHoursEnabled, quietStartHour, quietStartMinute,
                    quietEndHour, quietEndMinute, proactiveMinGapMinutes, proactiveMaxUnanswered,
                )
                scheduleType == AutomationScheduleType.SILENCE -> scheduler.scheduleSilence(
                    id, prompt.trim(), requireNotNull(silenceMinutes), notify,
                    requireNotNull(targetSessionId), actorName, quietHoursEnabled,
                    quietStartHour, quietStartMinute, quietEndHour, quietEndMinute,
                    proactiveMinGapMinutes, proactiveMaxUnanswered,
                )
                recurringMinutes == null -> scheduler.scheduleOnce(
                    id, prompt.trim(), firstRunAt, notify, mode, targetSessionId, actorName,
                    quietHoursEnabled, quietStartHour, quietStartMinute, quietEndHour,
                    quietEndMinute, proactiveMinGapMinutes, proactiveMaxUnanswered,
                )
                else -> scheduler.schedulePeriodic(
                    id, prompt.trim(), recurringMinutes, firstRunAt, notify, mode,
                    targetSessionId, actorName, quietHoursEnabled, quietStartHour,
                    quietStartMinute, quietEndHour, quietEndMinute, proactiveMinGapMinutes,
                    proactiveMaxUnanswered,
                    scheduleType,
                )
            }
        }.onFailure { AppLog.failure("TaskScheduling", "create_scheduled_task", it) }
            .isSuccess
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
    ): Boolean = runCatching {
        scheduler.updateChatInteractionPolicy(
            id = task.id,
            quietHoursEnabled = quietHoursEnabled,
            quietStartHour = quietStartHour,
            quietStartMinute = quietStartMinute,
            quietEndHour = quietEndHour,
            quietEndMinute = quietEndMinute,
            proactiveMinGapMinutes = proactiveMinGapMinutes,
            proactiveMaxUnanswered = proactiveMaxUnanswered,
            notify = notify,
        )
    }.onFailure { AppLog.failure("TaskScheduling", "update_chat_policy", it) }
        .getOrDefault(false).also { if (it) refresh() }

    private fun canPlanChat(snapshot: LocalHarnessTaskState): Boolean =
        snapshot.usageMode == LocalUsageMode.CHAT &&
            !snapshot.groupChat.enabled &&
            snapshot.sessionId.isNotBlank()
}
