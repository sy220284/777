package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.TokenUsageAggregate
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageGroupKind
import com.labteto.dshmobile.ui.theme.DshTheme
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalChatTurnUsageTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun lateUsageUpdatesSourceTurnAndSwitchingSessionClosesItsDetails() {
        val session = mutableStateOf("chat-a")
        val version = MutableStateFlow(0L)
        val tokens = AtomicLong(30)
        val opened = AtomicReference<String>()
        compose.setContent {
            DshTheme {
                val usage = LocalChatTurnUsageController(
                    sessionId = session.value, enabled = true, revision = version,
                    loadSummaries = { id -> mapOf("user-1" to TokenUsageAggregate(
                        inputTokens = if (id == "chat-a") tokens.get() else 7,
                        requestCount = 2, unreportedRequestCount = 1,
                    )) },
                    loadTurn = { id, turn ->
                        opened.set("$id/$turn")
                        TokenUsageGroupDetail(TokenUsageGroupKind.TURN, turn, "回合-$id", TokenUsageAggregate(inputTokens = tokens.get()), emptyList(), emptyList(), emptyList())
                    },
                    loadRequest = { null },
                )
                Column {
                    usage.summaries["user-1"]?.let { LocalChatTurnUsageButton(it) { usage.open("user-1") } }
                }
            }
        }
        val initial = context.getString(R.string.local_chat_turn_usage_incomplete, 30L)
        awaitText(initial)
        compose.onNodeWithText(initial).performClick()
        awaitText("回合-chat-a")
        assertEquals("chat-a/user-1", opened.get())
        compose.runOnIdle { tokens.set(50); version.value += 1 }
        awaitText(context.getString(R.string.local_run_usage_known, 50L))
        compose.runOnIdle { session.value = "chat-b" }
        awaitText(context.getString(R.string.local_chat_turn_usage_incomplete, 7L))
        assertEquals(0, compose.onAllNodesWithText("回合-chat-a").fetchSemanticsNodes().size)
        compose.onNodeWithText(context.getString(R.string.local_chat_turn_usage_incomplete, 7L)).performClick()
        awaitText("回合-chat-b")
        assertEquals("chat-b/user-1", opened.get())
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
