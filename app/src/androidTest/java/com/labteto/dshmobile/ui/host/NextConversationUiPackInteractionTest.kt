package com.labteto.dshmobile.ui.host

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.labteto.dshmobile.uicontract.ChatSceneSnapshot
import com.labteto.dshmobile.uicontract.ChatUiCommand
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.UiActionAvailability
import com.labteto.dshmobile.uicontract.UiApprovalImpact
import com.labteto.dshmobile.uicontract.UiApprovalSnapshot
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiCommandRejection
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.labteto.dshmobile.uicontract.UiSceneStatus
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.WorkConversationSceneSnapshot
import com.labteto.dshmobile.uicontract.WorkConversationUiCommand
import com.labteto.dshmobile.uicontract.WorkConversationUiPort
import com.labteto.dshmobile.uipack.next.NextConversationUiPack
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Renderer/contract tests with explicit fixtures, not proof of real model/tool completion. */
class NextConversationUiPackInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val drafts = com.labteto.dshmobile.ui.screens.local.LocalSessionDraftCache()
    private val identity = UiSessionIdentity("session", "activation", 0, "profile", null)
    private inner class Chat : ChatUiPort {
        override val snapshots = MutableStateFlow(ChatSceneSnapshot(identity, UiSceneStatus.EMPTY,
            UiExecutionPhase.IDLE, emptyList(), 0, UiActionAvailability(true), null))
        var receipt: UiCommandReceipt = UiCommandReceipt.Rejected(UiCommandRejection.QUEUE_FULL)
        val commands = mutableListOf<ChatUiCommand>()
        override fun snapshot() = snapshots.value
        override fun submit(command: ChatUiCommand): UiCommandReceipt {
            commands += command
            return receipt
        }
    }
    private inner class Work : WorkConversationUiPort {
        override val snapshots = MutableStateFlow(WorkConversationSceneSnapshot(identity, UiSceneStatus.EMPTY,
            UiExecutionPhase.WAITING_FOR_USER, emptyList(), 0, UiActionAvailability(true),
            UiApprovalSnapshot("pending", "write_file", "Review write", "{path:report}", UiApprovalImpact.HIGH), null))
        val commands = mutableListOf<WorkConversationUiCommand>()
        override fun snapshot() = snapshots.value
        override fun submit(command: WorkConversationUiCommand): UiCommandReceipt {
            commands += command
            return UiCommandReceipt.Rejected(UiCommandRejection.APPROVAL_EXPIRED)
        }
    }

    @Test fun rejectionKeepsDraftAndAcceptanceClearsItUsingTheCapturedIdentity() {
        val chat = Chat()
        val work = Work()
        compose.setContent { NextConversationUiPack.Render(drafts, chat, work, workMode = false, onClose = {}) }
        compose.onNode(hasSetTextAction()).performTextInput("retain draft")
        compose.onNodeWithText("发送").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("retain draft")
        compose.onNodeWithText("输入队列已满", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(ChatUiCommand.SendText(identity, "retain draft")), chat.commands)
            chat.receipt = UiCommandReceipt.Accepted
        }
        compose.onNodeWithText("发送").performClick()
        compose.onNodeWithText("retain draft", substring = true).assertDoesNotExist()
    }

    @Test fun approvalOnlySubmitsTheExistingPendingIdentityAndDisplaysOwnerRejection() {
        val chat = Chat()
        val work = Work()
        compose.setContent { NextConversationUiPack.Render(drafts, chat, work, workMode = true, onClose = {}) }
        compose.onNodeWithText("{path:report}").assertIsDisplayed()
        compose.onNodeWithText("批准本次操作").performClick()
        compose.runOnIdle {
            assertEquals(listOf(WorkConversationUiCommand.AnswerApproval(identity, "pending", true)), work.commands)
        }
        compose.onNodeWithText("这项审批已结束", substring = true).assertIsDisplayed()
    }
    @Test fun reenteringThePackKeepsTheHostDraftAndSessionSwitchDoesNotMixText() {
        val chat = Chat()
        val work = Work()
        val visible = androidx.compose.runtime.mutableStateOf(true)
        drafts.updateDraft(identity.sessionId, "host draft")
        compose.setContent {
            if (visible.value) NextConversationUiPack.Render(drafts, chat, work, workMode = false, onClose = {})
        }
        compose.onNode(hasSetTextAction()).assertTextContains("host draft")
        compose.runOnIdle { visible.value = false }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle {
            drafts.updateDraft(identity.sessionId, "edited outside pack")
            visible.value = true
        }
        compose.onNode(hasSetTextAction()).assertTextContains("edited outside pack")
        compose.runOnIdle {
            chat.snapshots.value = chat.snapshots.value.copy(identity = identity.copy(sessionId = "other"))
        }
        compose.onNode(hasSetTextAction()).performTextInput("other draft")
        compose.runOnIdle {
            assertEquals("edited outside pack", drafts.drafts.value[identity.sessionId])
            assertEquals("other draft", drafts.drafts.value["other"])
            chat.snapshots.value = chat.snapshots.value.copy(identity = identity)
        }
        compose.onNode(hasSetTextAction()).assertTextContains("edited outside pack")
    }

}
