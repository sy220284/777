package com.labteto.dshmobile.ui.host

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.v6.V6AutomationScene
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** New chat automations must never leak an old draft to a newly activated story. */
class V6AutomationContextInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val first = UiSessionIdentity("chat-1", "story-a", 1L, null, null)
    private val second = UiSessionIdentity("chat-2", "story-b", 2L, null, null)

    private class AutomationOwner(
        identity: UiSessionIdentity,
        private val beforeSubmitReturns: (() -> Unit)? = null,
    ) : AutomationUiPort {
        override val snapshots = MutableStateFlow(
            UiAutomationSnapshot(UiAutomationContext(identity, LocalUsageMode.CHAT, false, "Persona"),
                emptyList())
        )
        override val planningChanges = MutableStateFlow("")
        override fun planningRevision() = "rev"
        override fun snapshot() = snapshots.value
        // AutomationUiPort.submit executes on Dispatchers.IO; ensure cross-thread assertions
        // observe the same synchronized submission list without a flaky visibility race.
        val submissions = java.util.concurrent.CopyOnWriteArrayList<AutomationUiCommand>()
        override fun submit(command: AutomationUiCommand): AutomationUiReceipt {
            beforeSubmitReturns?.invoke()
            submissions += command
            return AutomationUiReceipt.Applied("task-id")
        }
        override suspend fun planAndSave(command: PlanChatAutomationCommand): AutomationUiReceipt =
            error("Direct V6 editor must use the ordinary typed Automation Owner command")
        fun change(identity: UiSessionIdentity) {
            snapshots.value = snapshots.value.copy(context = snapshots.value.context.copy(identity = identity))
        }
    }

    private fun show(owner: AutomationOwner) {
        compose.setContent {
            MaterialTheme {
                V6AutomationScene(owner, UiSceneId("automation.persona_interaction"), onBack = {})
            }
        }
        compose.onNodeWithText("新建任务").performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("提醒我完成任务")
        compose.onNodeWithText("创建任务").assertIsEnabled()
    }

    @Test fun crossingSessionActivationInvalidatesPreviouslyEditedChatAutomation() {
        val owner = AutomationOwner(first)
        show(owner)
        compose.runOnIdle { owner.change(second) }
        compose.onNodeWithText("创建任务").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(owner.submissions.isEmpty()) }
    }

    @Test fun dailyTaskSubmittedFromV6RetainsCalendarRecurrenceAndChatIdentity() {
        val owner = AutomationOwner(first)
        show(owner)
        compose.onNodeWithText("每天").performScrollTo().performClick()
        compose.onNodeWithText("创建任务").performScrollTo().performClick()
        compose.waitUntil(10_000) { owner.submissions.size == 1 }
        compose.runOnIdle {
            val command = owner.submissions.single() as AutomationUiCommand.Create
            assertEquals(first, command.identity)
            assertEquals(com.labteto.dshmobile.automation.AutomationScheduleType.DAILY,
                command.schedule.scheduleType)
            assertEquals(1440L, command.schedule.recurringMinutes)
        }
    }

    @Test fun slowOldSessionSubmissionCannotCloseStaleEditorAfterActivationChanges() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val owner = AutomationOwner(first) {
            started.countDown()
            check(release.await(8, java.util.concurrent.TimeUnit.SECONDS)) {
                "Timed out releasing the stale automation submission"
            }
        }
        try {
            show(owner)
            compose.onNodeWithText("创建任务").performScrollTo().performClick()
            compose.waitUntil(10_000) { started.count == 0L }
            compose.runOnIdle { owner.change(second) }
            release.countDown()
            compose.waitUntil(10_000) { owner.submissions.size == 1 }
            // An applied result from the previous session cannot complete this editor.
            compose.onNodeWithText("创建任务").assertIsNotEnabled()
            compose.runOnIdle {
                assertEquals(first, (owner.submissions.single() as AutomationUiCommand.Create).identity)
            }
        } finally {
            release.countDown()
        }
    }

    @Test fun restartingEditorAfterSwitchBindsOnlyTheNewOwnerIdentity() {
        val owner = AutomationOwner(first)
        show(owner)
        compose.runOnIdle { owner.change(second) }
        compose.onNodeWithText("创建任务").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("新建任务")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("新建任务").performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("属于新会话的计划")
        compose.onNodeWithText("创建任务").assertIsEnabled()
        compose.onNodeWithText("创建任务").performScrollTo().performClick()
        compose.waitUntil(10_000) { owner.submissions.size == 1 }
        compose.runOnIdle {
            assertEquals(1, owner.submissions.size)
            val command = owner.submissions.single() as AutomationUiCommand.Create
            assertEquals(second, command.identity)
            assertEquals(AutomationMode.CHAT, command.schedule.mode)
            assertEquals("属于新会话的计划", command.schedule.prompt)
        }
    }
}
