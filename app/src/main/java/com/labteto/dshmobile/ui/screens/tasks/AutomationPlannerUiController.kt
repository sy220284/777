package com.labteto.dshmobile.ui.screens.tasks

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.automation.AutomationPlanDraft
import com.labteto.dshmobile.local.presentation.LocalHarnessTaskState
import com.labteto.dshmobile.local.presentation.LocalAutomationPlanningFacade
import com.labteto.dshmobile.local.presentation.LocalTaskRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AutomationPlannerUiController(
    private val scope: CoroutineScope,
    private val localRuntime: LocalTaskRuntime,
    private val planningService: LocalAutomationPlanningFacade,
    private val _state: MutableStateFlow<TasksUiState>,
    private val save: (AutomationPlanDraft, String?, String) -> Boolean,
    private val tasks: () -> List<com.labteto.dshmobile.automation.AutomationTask>,
) {
    private var revision = 0L
    private var planJob: Job? = null
    init {
        scope.launch {
            var owner = planningService.currentRevision()
            planningService.revisions.collect { current ->
                if (current != owner) {
                    owner = current
                    revision += 1
                    planJob?.cancel()
                    _state.update { it.copy(planning = false, plannerError = null) }
                }
            }
        }
    }
    fun submitChatPlan(input: String, editingTaskId: String? = null) {
        val snapshot = localRuntime.snapshot()
        if (!canPlanChat(snapshot) || input.isBlank() || _state.value.planning) return
        val expectedSessionId = snapshot.sessionId
        val request = ++revision
        _state.update { it.copy(planning = true, plannerError = null) }
        planJob = scope.launch {
            try {
            runCatching { planningService.plan(input) }
                .onSuccess { draft ->
                    if (request != revision) return@onSuccess
                    val latest = localRuntime.snapshot()
                    if (latest.sessionId != expectedSessionId ||
                        draft.sourceSessionId != expectedSessionId ||
                        !planningService.isCurrent(draft)
                    ) {
                        _state.update {
                            it.copy(
                                planning = false,
                                plannerError = PlannerUiError.SESSION_CHANGED,
                            )
                        }
                        return@onSuccess
                    }
                    val saved = withContext(Dispatchers.IO) { save(draft, editingTaskId, expectedSessionId) }
                    if (request != revision) return@onSuccess
                    _state.update {
                        if (saved) {
                            it.copy(
                                tasks = tasks(),
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
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (request != revision) return@onFailure
                    if (localRuntime.snapshot().sessionId != expectedSessionId) {
                        _state.update { it.copy(planning = false, plannerError = PlannerUiError.SESSION_CHANGED) }
                        return@onFailure
                    }
                    _state.update {
                        it.copy(
                            planning = false,
                            plannerError = PlannerUiError.PLAN_FAILED,
                        )
                    }
                }
            } finally {
                if (request == revision) _state.update { it.copy(planning = false) }
            }
        }
    }
    private fun canPlanChat(snapshot: LocalHarnessTaskState): Boolean =
        snapshot.usageMode == LocalUsageMode.CHAT && !snapshot.groupChat.enabled && snapshot.sessionId.isNotBlank()
}
