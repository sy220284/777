package com.labteto.dshmobile.ui.screens.tasks
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.automation.HarnessAutomationScheduler
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.automation.AutomationPlanDraft
import com.labteto.dshmobile.local.automation.AutomationPlanningService
import com.labteto.dshmobile.local.presentation.LocalHarnessTaskState
import com.labteto.dshmobile.local.presentation.LocalTaskRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
enum class TasksNotice { CANCELLED, MISSING, RUN_STARTED, RUN_FAILED }
internal enum class PlannerUiError { SUGGESTIONS_FAILED, SESSION_CHANGED, SAVE_FAILED, PLAN_FAILED }
internal enum class AutomationCadence { ONCE, DAILY, WEEKLY, CUSTOM }
data class TasksUiState(
    val tasks: List<AutomationTask> = emptyList(),
    val notice: TasksNotice? = null,
    val plannerSuggestions: List<String> = emptyList(),
    val suggestionsLoading: Boolean = false,
    val suggestionSessionId: String? = null,
    val planning: Boolean = false,
    val plannerError: PlannerUiError? = null,
    val saveRevision: Long = 0L,
)
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val scheduler: HarnessAutomationScheduler,
    private val localRuntime: LocalTaskRuntime,
    private val planningService: AutomationPlanningService,
) : ViewModel() {
    val harnessState: StateFlow<LocalHarnessTaskState> = localRuntime.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = localRuntime.initialState,
    )
    private val _state = MutableStateFlow(TasksUiState(tasks = scheduler.list()))
    val state: StateFlow<TasksUiState> = _state.asStateFlow()
    fun acknowledgeNotice(notice: TasksNotice) {
        if (_state.value.notice == notice) _state.update { it.copy(notice = null) }
    }
    fun refresh() = _state.update { it.copy(tasks = scheduler.list(), notice = null) }
    fun cancel(id: String) {
        val removed = scheduler.cancelTask(id)
        _state.update {
            it.copy(
                tasks = scheduler.list(),
                notice = if (removed) TasksNotice.CANCELLED else TasksNotice.MISSING,
            )
        }
    }
    fun pause(id: String) { scheduler.pauseTask(id); refresh() }
    fun resume(id: String) { scheduler.resumeTask(id); refresh() }
    fun runNow(id: String): Boolean {
        val started = scheduler.runTaskNow(id)
        _state.update {
            it.copy(
                tasks = scheduler.list(),
                notice = if (started) TasksNotice.RUN_STARTED else TasksNotice.RUN_FAILED,
            )
        }
        return started
    }
    fun loadChatSuggestions(force: Boolean = false) {
        val snapshot = localRuntime.snapshot()
        if (!canPlanChat(snapshot)) {
            _state.update {
                it.copy(
                    plannerSuggestions = emptyList(),
                    suggestionsLoading = false,
                    suggestionSessionId = snapshot.sessionId.takeIf(String::isNotBlank),
                )
            }
            return
        }
        val current = _state.value
        if (!force && current.suggestionSessionId == snapshot.sessionId &&
            (current.suggestionsLoading || current.plannerSuggestions.isNotEmpty())
        ) return
        val expectedSessionId = snapshot.sessionId
        _state.update {
            it.copy(
                suggestionsLoading = true,
                plannerError = null,
                suggestionSessionId = expectedSessionId,
            )
        }
        viewModelScope.launch {
            runCatching { planningService.suggestions() }
                .onSuccess { result ->
                    if (localRuntime.snapshot().sessionId != expectedSessionId ||
                        result.sourceSessionId != expectedSessionId
                    ) return@onSuccess
                    _state.update {
                        it.copy(
                            plannerSuggestions = result.suggestions,
                            suggestionsLoading = false,
                            suggestionSessionId = expectedSessionId,
                        )
                    }
                }
                .onFailure { error ->
                    if (localRuntime.snapshot().sessionId != expectedSessionId) return@onFailure
                    _state.update {
                        it.copy(
                            suggestionsLoading = false,
                            plannerError = PlannerUiError.SUGGESTIONS_FAILED,
                        )
                    }
                }
        }
    }
    fun submitChatPlan(input: String, editingTaskId: String? = null) {
        val snapshot = localRuntime.snapshot()
        if (!canPlanChat(snapshot) || input.isBlank() || _state.value.planning) return
        val expectedSessionId = snapshot.sessionId
        _state.update { it.copy(planning = true, plannerError = null) }
        viewModelScope.launch {
            runCatching { planningService.plan(input) }
                .onSuccess { draft ->
                    val latest = localRuntime.snapshot()
                    if (latest.sessionId != expectedSessionId ||
                        draft.sourceSessionId != expectedSessionId
                    ) {
                        _state.update {
                            it.copy(
                                planning = false,
                                plannerError = PlannerUiError.SESSION_CHANGED,
                            )
                        }
                        return@onSuccess
                    }
                    val saved = saveChatDraft(draft, editingTaskId, expectedSessionId)
                    _state.update {
                        if (saved) {
                            it.copy(
                                tasks = scheduler.list(),
                                planning = false,
                                plannerError = null,
                                saveRevision = it.saveRevision + 1L,
                            )
                        } else {
                            it.copy(
                                planning = false,
                                plannerError = PlannerUiError.SAVE_FAILED,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (localRuntime.snapshot().sessionId != expectedSessionId) return@onFailure
                    _state.update {
                        it.copy(
                            planning = false,
                            plannerError = PlannerUiError.PLAN_FAILED,
                        )
                    }
                }
        }
    }
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
    ): Boolean = runCatching {
        scheduler.updateTask(
            id, prompt, firstRunAt, recurringMinutes, scheduleType, silenceMinutes,
            windowStartMinuteOfDay, windowEndMinuteOfDay, quietHoursEnabled,
            quietStartHour, quietStartMinute, quietEndHour, quietEndMinute,
            proactiveMinGapMinutes, proactiveMaxUnanswered,
        )
    }.getOrDefault(false).also { if (it) refresh() }
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
        if (scheduleType !in setOf(AutomationScheduleType.SILENCE, AutomationScheduleType.WINDOW) &&
            firstRunAt <= now
        ) return false
        val minimumRecurringMinutes = if (mode == AutomationMode.CHAT) 60L else 15L
        if (recurringMinutes != null && recurringMinutes < minimumRecurringMinutes) return false
        if (scheduleType == AutomationScheduleType.SILENCE &&
            (silenceMinutes == null || silenceMinutes < 60L)
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
            val id = "ui-" + System.currentTimeMillis()
            val targetSessionId = snapshot.sessionId.takeIf { mode == AutomationMode.CHAT }
            val actorName = snapshot.chatPersona.name.takeIf {
                mode == AutomationMode.CHAT && it.isNotBlank()
            }
            when {
                scheduleType == AutomationScheduleType.WINDOW -> scheduler.scheduleWindow(
                    id, prompt.trim(), requireNotNull(windowStartMinuteOfDay),
                    requireNotNull(windowEndMinuteOfDay), true, requireNotNull(targetSessionId),
                    actorName, quietHoursEnabled, quietStartHour, quietStartMinute,
                    quietEndHour, quietEndMinute, proactiveMinGapMinutes, proactiveMaxUnanswered,
                )
                scheduleType == AutomationScheduleType.SILENCE -> scheduler.scheduleSilence(
                    id, prompt.trim(), requireNotNull(silenceMinutes), true,
                    requireNotNull(targetSessionId), actorName, quietHoursEnabled,
                    quietStartHour, quietStartMinute, quietEndHour, quietEndMinute,
                    proactiveMinGapMinutes, proactiveMaxUnanswered,
                )
                recurringMinutes == null -> scheduler.scheduleOnce(
                    id, prompt.trim(), firstRunAt, true, mode, targetSessionId, actorName,
                    quietHoursEnabled, quietStartHour, quietStartMinute, quietEndHour,
                    quietEndMinute, proactiveMinGapMinutes, proactiveMaxUnanswered,
                )
                else -> scheduler.schedulePeriodic(
                    id, prompt.trim(), recurringMinutes, firstRunAt, true, mode,
                    targetSessionId, actorName, quietHoursEnabled, quietStartHour,
                    quietStartMinute, quietEndHour, quietEndMinute, proactiveMinGapMinutes,
                    proactiveMaxUnanswered,
                    if (mode == AutomationMode.CHAT) scheduleType else AutomationScheduleType.LEGACY,
                )
            }
            refresh()
        }.isSuccess
    }
    private fun canPlanChat(snapshot: LocalHarnessTaskState): Boolean =
        snapshot.usageMode == LocalUsageMode.CHAT &&
            !snapshot.groupChat.enabled &&
            snapshot.sessionId.isNotBlank()
}
