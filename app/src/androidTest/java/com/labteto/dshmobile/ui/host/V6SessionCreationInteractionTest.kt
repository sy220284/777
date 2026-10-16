package com.labteto.dshmobile.ui.host

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.labteto.dshmobile.uihost.UiSceneNavigationState
import com.labteto.dshmobile.uipack.v6.V6SessionShellScene
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exact native V6 session creation and Host navigation handshake. */
class V6SessionCreationInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val identity = UiSessionIdentity("session-1", "activation", 4L, null, null)

    private class SessionOwner(private val identity: UiSessionIdentity) : SessionUiPort {
        override val snapshots = MutableStateFlow(
            UiSessionsSnapshot(identity, LocalUsageMode.CHAT, false, emptyList(), null)
        )
        var receipt: UiCommandReceipt = UiCommandReceipt.Accepted
        var summary: String? = null
        val submitted = mutableListOf<SessionUiCommand>()
        override fun snapshot() = snapshots.value
        override fun submit(command: SessionUiCommand): UiCommandReceipt {
            submitted.add(command)
            return receipt
        }
        override fun handoffSummary(identity: UiSessionIdentity): String? = summary
        override suspend fun delete(sessionIds: Set<String>): UiSessionDeletionReceipt =
            UiSessionDeletionReceipt.Deleted(sessionIds)
    }

    private fun show(owner: SessionOwner, destinations: MutableList<UiSceneId>) {
        compose.setContent {
            MaterialTheme {
                V6SessionShellScene(UiSceneId("shell.new_session"), owner,
                    onNavigate = { id ->
                        destinations.add(id)
                        UiSceneNavigationResult.Changed(
                            UiSceneNavigationState("fixture-v6", listOf(UiSceneId("route.home"), id)))
                    },
                    onBack = {})
            }
        }
        compose.onNodeWithText("创建本地会话").assertExists()
    }

    @Test fun acceptedCreateIsPassedToOriginalOwnerAndOpensHomeOnce() {
        val owner = SessionOwner(identity)
        val destinations = mutableListOf<UiSceneId>()
        show(owner, destinations)
        compose.onNodeWithText("创建本地会话").performClick()
        compose.runOnIdle {
            assertEquals(listOf(SessionUiCommand.Create(identity,
                LocalConversationMode.INDEPENDENT, LocalUsageMode.CHAT, null)), owner.submitted)
            assertEquals(listOf(UiSceneId("route.home")), destinations)
        }
    }

    @Test fun rejectedCreateNeverChangesHostNavigation() {
        val owner = SessionOwner(identity)
        owner.receipt = UiCommandReceipt.Rejected(UiCommandRejection.SESSION_TRANSITION)
        val destinations = mutableListOf<UiSceneId>()
        show(owner, destinations)
        compose.onNodeWithText("创建本地会话").performClick()
        compose.runOnIdle {
            assertEquals(1, owner.submitted.size)
            assertTrue(destinations.isEmpty())
        }
    }

    @Test fun continuationWithoutOwnerHandoffSummaryCannotForgeCrossStoryContext() {
        val owner = SessionOwner(identity)
        val destinations = mutableListOf<UiSceneId>()
        show(owner, destinations)
        compose.onNodeWithText("CONTINUATION").performClick()
        compose.onNodeWithText("创建本地会话").performClick()
        compose.runOnIdle {
            assertTrue(owner.submitted.isEmpty())
            assertTrue(destinations.isEmpty())
        }
        compose.onNodeWithText("当前会话没有可复用的续聊摘要").assertExists()
    }
}
