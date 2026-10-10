package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAggregate
import com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageGroupKind
import com.labteto.dshmobile.local.TokenUsageGroupSummary
import com.labteto.dshmobile.ui.theme.DshTheme
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalRunCenterUsageTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun missingUsageIsExplicitAndTaskDetailsRefreshWithoutCrossingSessions() {
        val session = mutableStateOf("session-a")
        val revision = MutableStateFlow(0L)
        val tokens = AtomicLong(60)
        val selectedTask = AtomicReference<String>()
        compose.setContent {
            DshTheme {
                LocalRunCenterUsageSection(
                    sessionId = session.value,
                    revision = revision,
                    loadSession = { id ->
                        val total = if (id == "session-a") tokens.get() else 20L
                        val aggregate = TokenUsageAggregate(inputTokens = total, requestCount = 1, unreportedRequestCount = 1)
                        TokenUsageAnalyticsSnapshot(tracked = aggregate, tasks = listOf(
                            TokenUsageGroupSummary("root-$id", "任务-$id", LocalUsageMode.WORK, aggregate, 1),
                        ))
                    },
                    loadTask = { id ->
                        selectedTask.set(id)
                        TokenUsageGroupDetail(TokenUsageGroupKind.TASK, id, "当前任务明细", TokenUsageAggregate(inputTokens = tokens.get()), emptyList(), emptyList(), emptyList())
                    },
                    loadRequest = { null },
                )
            }
        }
        awaitText(context.getString(R.string.local_run_usage_incomplete, 60L))
        compose.onNodeWithText(context.getString(R.string.local_run_usage_incomplete, 60L)).performClick()
        compose.onNodeWithText(context.getString(R.string.local_run_usage_missing, 1L)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.local_run_usage_task, "任务-session-a", 60L)).performClick()
        awaitText("当前任务明细")
        assertEquals("root-session-a", selectedTask.get())
        compose.onNodeWithText(context.getString(R.string.common_back)).performClick()
        compose.runOnIdle { tokens.set(90); revision.value += 1 }
        awaitText(context.getString(R.string.local_run_usage_known, 90L))
        compose.runOnIdle { session.value = "session-b" }
        awaitText(context.getString(R.string.local_run_usage_incomplete, 20L))
        assertEquals(0, compose.onAllNodesWithText(context.getString(R.string.local_run_usage_known, 90L)).fetchSemanticsNodes().size)
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
