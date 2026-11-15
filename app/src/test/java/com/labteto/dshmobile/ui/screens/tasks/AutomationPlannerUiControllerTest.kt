package com.labteto.dshmobile.ui.screens.tasks

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.uicontract.AutomationUiCommand
import com.labteto.dshmobile.uicontract.AutomationUiPort
import com.labteto.dshmobile.uicontract.AutomationUiReceipt
import com.labteto.dshmobile.uicontract.PlanChatAutomationCommand
import com.labteto.dshmobile.uicontract.UiAutomationContext
import com.labteto.dshmobile.uicontract.UiAutomationRejection
import com.labteto.dshmobile.uicontract.UiAutomationSnapshot
import com.labteto.dshmobile.uicontract.UiConversationMode
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationPlannerUiControllerTest {
    private val identity = UiSessionIdentity("chat-1", "activation-1", 1L, null, null, UiConversationMode.CHAT)

    private class StubPort(val identity: UiSessionIdentity) : AutomationUiPort {
        var receipt: AutomationUiReceipt = AutomationUiReceipt.Applied()
        var planCalls = 0
        var submitted: PlanChatAutomationCommand? = null
        val owner = UiAutomationContext(identity, LocalUsageMode.CHAT, false, "角色")
        private val changes = MutableStateFlow("revision-1")
        override val planningChanges = changes
        override fun planningRevision(): String = changes.value
        override val snapshots = MutableStateFlow(UiAutomationSnapshot(owner, emptyList()))
        override fun context() = owner
        override fun snapshot() = snapshots.value
        override fun submit(command: AutomationUiCommand) = error("Planner must never bypass planAndSave")
        override suspend fun planAndSave(command: PlanChatAutomationCommand): AutomationUiReceipt {
            planCalls++
            submitted = command
            return receipt
        }
    }

    @Test
    fun planningUsesSingleFeatureCommandInsteadOfSeparatePlannerAndSchedulerWrites() = runBlocking {
        val port = StubPort(identity)
        val state = MutableStateFlow(TasksUiState())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = AutomationPlannerUiController(scope, port, state)
            controller.submitChatPlan("在明天提醒角色整理任务", "task-1")
            withTimeout(5_000) { state.first { it.saveRevision == 1L } }
            assertEquals(1, port.planCalls)
            assertEquals(identity, port.submitted?.identity)
            assertEquals("task-1", port.submitted?.editingTaskId)
            assertFalse(state.value.planning)
            assertEquals(null, state.value.plannerError)
        } finally { scope.cancel() }
    }

    @Test
    fun staleTaskIsShownAsSessionChangeInsteadOfFalseSuccess() = runBlocking {
        val port = StubPort(identity).apply {
            receipt = AutomationUiReceipt.Rejected(UiAutomationRejection.STALE_TASK)
        }
        val state = MutableStateFlow(TasksUiState())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = AutomationPlannerUiController(scope, port, state)
            controller.submitChatPlan("更新角色事件", "task-1")
            withTimeout(5_000) { state.first { it.plannerError == PlannerUiError.SESSION_CHANGED } }
            assertEquals(0L, state.value.saveRevision)
            assertEquals(1, port.planCalls)
        } finally { scope.cancel() }
    }

    @Test
    fun plannerFailureShowsOriginalReasonRatherThanHidingIt() = runBlocking {
        val port = StubPort(identity).apply {
            receipt = AutomationUiReceipt.Failed("执行时间无效，请重新确认时区")
        }
        val state = MutableStateFlow(TasksUiState())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = AutomationPlannerUiController(scope, port, state)
            controller.submitChatPlan("下周触发事件")
            withTimeout(5_000) { state.first { it.plannerError == PlannerUiError.PLAN_FAILED } }
            assertTrue(state.value.plannerFailureReason.orEmpty().contains("时区"))
            assertEquals(0L, state.value.saveRevision)
        } finally { scope.cancel() }
    }
}
