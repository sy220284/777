package com.labteto.dshmobile.ui.host

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.local.interaction.LocalApprovalMode
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.v6.V6WorkSteeringScene
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** V6 policy display cannot execute an individual pending tool approval by changing policy. */
class V6WorkPolicyInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val identity = UiSessionIdentity("work-a", "activation-a", 10L, null, null)
    private val other = UiSessionIdentity("work-b", "activation-b", 11L, null, null)

    private class Policy(identity: UiSessionIdentity) : WorkPolicyUiPort {
        override val snapshots = MutableStateFlow(
            UiWorkPolicySnapshot(identity, false, true, LocalApprovalMode.MANUAL)
        )
        val calls = mutableListOf<WorkPolicyUiCommand>()
        override fun snapshot() = snapshots.value
        override fun submit(command: WorkPolicyUiCommand): UiCommandReceipt {
            calls += command
            return UiCommandReceipt.Accepted
        }
    }
    private fun show(owner: Policy) {
        compose.setContent {
            MaterialTheme { V6WorkSteeringScene(owner, onBack = {}) }
        }
        compose.onNodeWithText("Work 运行策略").assertExists()
    }

    @Test fun automaticPolicyRequiresExplicitConfirmationAndDoesNotApprovePendingTool() {
        val owner = Policy(identity)
        show(owner)
        compose.onNodeWithText("符合策略的请求自动批准").performClick()
        compose.runOnIdle { assertTrue(owner.calls.isEmpty()) }
        compose.onNodeWithText("确认变更 Work 审批模式").assertExists()
        compose.onNodeWithText("确认变更", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(WorkPolicyUiCommand.SetApprovalMode(identity, LocalApprovalMode.AUTO)),
                owner.calls
            )
            assertTrue(owner.calls.none {
                it is WorkPolicyUiCommand.ApproveWithAutoMode ||
                    it is WorkPolicyUiCommand.ApproveDeviceTurn
            })
        }
    }

    @Test fun sessionSwitchWhileConfirmationIsOpenCannotChangeNewSessionPolicy() {
        val owner = Policy(identity)
        show(owner)
        compose.onNodeWithText("符合策略的请求自动批准").performClick()
        compose.runOnIdle {
            owner.snapshots.value = owner.snapshots.value.copy(identity = other)
        }
        compose.runOnIdle { assertTrue(owner.calls.isEmpty()) }
        // Switching the Owner identity disposes the old identity-keyed confirmation.
        compose.onNodeWithText("确认变更 Work 审批模式").assertDoesNotExist()
    }

    @Test fun cancelChangesNoOwnerPolicyAndDeviceLeaseIsNotImplicitlyApproved() {
        val owner = Policy(identity)
        show(owner)
        compose.onNodeWithText("符合策略的请求自动批准").performClick()
        compose.onNodeWithText("取消", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertTrue(owner.calls.isEmpty()) }
        compose.onNodeWithText("撤销本轮设备授权").assertExists()
    }
}
