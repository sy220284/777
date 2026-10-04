package com.labteto.dshmobile.ui.screens.tasks

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.automation.AutomationPlanDraft
import com.labteto.dshmobile.local.automation.AutomationPlanningService
import com.labteto.dshmobile.local.presentation.LocalHarnessTaskState
import com.labteto.dshmobile.local.presentation.LocalTaskRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class AutomationPlannerUiController(
    private val scope: CoroutineScope,
    private val localRuntime: LocalTaskRuntime,
    private val planningService: AutomationPlanningService,
    private val _state: MutableStateFlow<TasksUiState>,
    private val save: (AutomationPlanDraft, String?, String) -> Boolean,
    private val tasks: () -> List<com.labteto.dshmobile.automation.AutomationTask>,
) {
    private var revision = 0L
    private var suggestionRevision = 0L
    private var planJob: Job? = null
    private var suggestionJob: Job? = null
    init {
        scope.launch {
            var owner = planningService.currentRevision()
            planningService.revisions.collect { current ->
                if (current != owner) {
                    owner = current
                    revision += 1
                    suggestionRevision += 1
                    planJob?.cancel()
                    suggestionJob?.cancel()
                    _state.update { it.copy(planning = false, suggestionsLoading = false,
                        plannerSuggestions = emptyList(), suggestionSessionId = null, plannerError = null) }
                }
            }
        }
    }
    fun loadChatSuggestions(force: Boolean = false) {
        val snapshot = localRuntime.snapshot()
        if (!canPlanChat(snapshot)) {
            suggestionRevision += 1
            suggestionJob?.cancel()
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
        suggestionJob?.cancel()
        val request = ++suggestionRevision
        _state.update {
            it.copy(
                plannerSuggestions = emptyList(),
                suggestionsLoading = true,
                plannerError = null,
                suggestionSessionId = expectedSessionId,
            )
        }
        suggestionJob = scope.launch {
            try {
            runCatching { planningService.suggestions() }
                .onSuccess { result ->
                    if (request != suggestionRevision) return@onSuccess
                    if (localRuntime.snapshot().sessionId != expectedSessionId ||
                        result.sourceSessionId != expectedSessionId ||
                        !planningService.isCurrent(result)
                    ) {
                        _state.update { it.copy(suggestionsLoading = false) }
                        return@onSuccess
                    }
                    _state.update {
                        it.copy(
                            plannerSuggestions = result.suggestions,
                            suggestionsLoading = false,
                            suggestionSessionId = expectedSessionId,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (request != suggestionRevision) return@onFailure
                    if (localRuntime.snapshot().sessionId != expectedSessionId) {
                        _state.update { it.copy(planning = false, suggestionsLoading = false, plannerError = PlannerUiError.SESSION_CHANGED) }
                        return@onFailure
                    }
                    _state.update {
                        it.copy(
                            suggestionsLoading = false,
                            plannerError = PlannerUiError.SUGGESTIONS_FAILED,
                        )
                    }
                }
            } finally {
                if (request == suggestionRevision) _state.update { it.copy(suggestionsLoading = false) }
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
                    val saved = save(draft, editingTaskId, expectedSessionId)
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
                        _state.update { it.copy(planning = false, suggestionsLoading = false, plannerError = PlannerUiError.SESSION_CHANGED) }
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
