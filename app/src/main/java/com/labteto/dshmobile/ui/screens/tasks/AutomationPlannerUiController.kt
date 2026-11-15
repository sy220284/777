package com.labteto.dshmobile.ui.screens.tasks

import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.uicontract.AutomationUiPort
import com.labteto.dshmobile.uicontract.AutomationUiReceipt
import com.labteto.dshmobile.uicontract.PlanChatAutomationCommand
import com.labteto.dshmobile.uicontract.UiAutomationRejection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A UI turn only. Plan validation, identity fencing and task writes belong to AutomationUiPort. */
internal class AutomationPlannerUiController(
    private val scope: CoroutineScope,
    private val automation: AutomationUiPort,
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
                    state.update { it.copy(planning = false, plannerError = null, plannerFailureReason = null) }
                }
            }
        }
    }

    fun submitChatPlan(input: String, editingTaskId: String? = null) {
        val context = automation.context()
        val identity = context.identity ?: return
        if (!context.canPlanChat || input.isBlank() || state.value.planning) return
        val request = ++revision
        state.update { it.copy(planning = true, plannerError = null, plannerFailureReason = null) }
        planJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    automation.planAndSave(PlanChatAutomationCommand(identity, input, editingTaskId))
                }
                if (request != revision) return@launch
                state.update { previous ->
                    when (result) {
                        is AutomationUiReceipt.Applied -> previous.copy(
                            planning = false,
                            plannerError = null,
                            plannerFailureReason = null,
                            saveRevision = previous.saveRevision + 1L,
                        )
                        is AutomationUiReceipt.Rejected -> previous.copy(
                            planning = false,
                            plannerError = if (result.reason in setOf(
                                    UiAutomationRejection.STALE_CONTEXT,
                                    UiAutomationRejection.STALE_TASK,
                                )) PlannerUiError.SESSION_CHANGED else PlannerUiError.SAVE_FAILED,
                            plannerFailureReason = null,
                        )
                        is AutomationUiReceipt.Failed -> previous.copy(
                            planning = false,
                            plannerError = PlannerUiError.PLAN_FAILED,
                            plannerFailureReason = result.message?.takeIf(String::isNotBlank),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.failure("TaskScheduling", "plan_and_save_chat_event", failure)
                if (request == revision) {
                    state.update { it.copy(planning = false, plannerError = PlannerUiError.PLAN_FAILED,
                        plannerFailureReason = failure.message?.takeIf(String::isNotBlank)) }
                }
            } finally {
                if (request == revision) state.update { it.copy(planning = false) }
            }
        }
    }
}
