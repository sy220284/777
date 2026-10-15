package com.labteto.dshmobile.ui.screens.tasks

import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI request lifecycle only. Model planning and scheduling admission remain in Automation. */
internal class AutomationPlannerUiController(
    private val scope: CoroutineScope, private val automation: AutomationUiPort,
    private val state: MutableStateFlow<TasksUiState>,
) {
    private var revision = 0L
    private var planJob: Job? = null
    init {
        scope.launch {
            var owner = automation.planningRevision()
            automation.planningChanges.collect { current ->
                if (current != owner) {
                    owner = current
                    revision += 1
                    planJob?.cancel()
                    state.update { it.copy(planning = false, plannerError = null) }
                }
            }
        }
    }
    fun submitChatPlan(input: String, editingTaskId: String? = null) {
        val context = automation.context()
        val identity = context.identity ?: return
        if (!context.canPlanChat || input.isBlank() || state.value.planning) return
        val request = ++revision
        state.update { it.copy(planning = true, plannerError = null) }
        planJob = scope.launch {
            try {
                val receipt = automation.planAndSave(PlanChatAutomationCommand(identity, input, editingTaskId))
                if (request != revision) return@launch
                val tasks = if (receipt is AutomationUiReceipt.Applied)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { automation.snapshot().tasks } else null
                if (request != revision) return@launch
                state.update { value -> when (receipt) {
                    is AutomationUiReceipt.Applied -> value.copy(tasks = tasks ?: value.tasks,
                        planning = false, plannerError = null, saveRevision = value.saveRevision + 1)
                    is AutomationUiReceipt.Rejected -> value.copy(planning = false, plannerError =
                        if (receipt.reason == UiAutomationRejection.STALE_CONTEXT) PlannerUiError.SESSION_CHANGED
                        else PlannerUiError.SAVE_FAILED)
                    is AutomationUiReceipt.Failed -> value.copy(planning = false, plannerError = PlannerUiError.PLAN_FAILED)
                } }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                com.labteto.dshmobile.observability.AppLog.failure("TaskScheduling", "plan_task", failure)
                if (request == revision) state.update { it.copy(planning = false, plannerError = PlannerUiError.PLAN_FAILED) }
            } finally {
                if (request == revision) state.update { it.copy(planning = false) }
            }
        }
    }
}
