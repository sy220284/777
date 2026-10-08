package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicLong
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Rule
import org.junit.Test

class LocalRunCenterAgentActionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun toolStatusRefreshesWhenEventSequenceAdvancesWithoutWorkStateChange() {
        val sequence = AtomicLong(1L)
        compose.setContent {
            DshTheme {
                LocalRunCenterScreen(
                    state = LocalWorkUiState(sessionId = "session-a"),
                    onJobOutput = { "" },
                    onArtifacts = { emptyList() },
                    onToolActivities = {
                        listOf(LocalToolActivityUiItem(
                            "call-1", "audit-tool",
                            if (sequence.get() == 1L) LocalToolUiPhase.RUNNING else LocalToolUiPhase.COMPLETED,
                        ))
                    },
                    onEventSequence = { sequence.get() },
                    onStopJob = { "" },
                    onStartBackgroundAgent = { LocalWorkUiActionResult(false, "") },
                    onSendAgentMessage = { _, _ -> LocalWorkUiActionResult(false, "") },
                    onOpenResults = {},
                    onDismiss = {},
                )
            }
        }
        // DsSheetChoiceRow deliberately uses distinct title/subtitle semantics nodes.
        // Verify the actual service row stays visible while its event-derived phase changes.
        val running = context.getString(R.string.local_tool_phase_running)
        val completed = context.getString(R.string.local_tool_phase_completed)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("audit-tool").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(running).fetchSemanticsNodes().isNotEmpty()
        }
        sequence.incrementAndGet()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("audit-tool").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(completed).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(running).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun idleRunCenterStillExposesDirectAgentLaunch() {
        compose.setContent {
            DshTheme {
                LocalRunCenterScreen(
                    state = LocalWorkUiState(sessionId = "session-a"),
                    onJobOutput = { "" },
                    onArtifacts = { emptyList() },
                    onStopJob = { "" },
                    onStartBackgroundAgent = { LocalWorkUiActionResult(false, "") },
                    onSendAgentMessage = { _, _ -> LocalWorkUiActionResult(false, "") },
                    onOpenResults = {},
                    onDismiss = {},
                )
            }
        }

        compose
            .onNodeWithContentDescription(context.getString(R.string.local_run_agent_start))
            .assertIsDisplayed()
            .performClick()

        compose
            .onNodeWithText(context.getString(R.string.local_run_agent_direct_title))
            .assertIsDisplayed()
    }

    @Test
    fun agentNeedingAttentionAppearsBeforeCurrentGoal() {
        compose.setContent {
            DshTheme {
                LocalRunCenterScreen(
                    state = LocalWorkUiState(
                        sessionId = "session-a",
                        goal = com.labteto.dshmobile.local.work.LocalGoal(
                            description = "整理发布材料",
                            status = "running",
                        ),
                        jobs = listOf(
                            LocalJobInfo(
                                id = "job-agent",
                                label = "等待确认的子代理",
                                status = "dormant",
                                ownerSessionId = "session-a",
                                isAgent = true,
                                canMessage = true,
                                continuable = true,
                                pendingMessageCount = 1,
                            ),
                        ),
                    ),
                    onJobOutput = { "" },
                    onArtifacts = { emptyList() },
                    onStopJob = { "" },
                    onStartBackgroundAgent = { LocalWorkUiActionResult(false, "") },
                    onSendAgentMessage = { _, _ -> LocalWorkUiActionResult(false, "") },
                    onOpenResults = {},
                    onDismiss = {},
                )
            }
        }

        val attention = compose
            .onNodeWithText(context.getString(R.string.local_run_needs_attention, 1))
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val goal = compose
            .onNodeWithText(context.getString(R.string.local_run_current_goal))
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        assertTrue(attention < goal)
    }

    @Test
    fun dormantAgentExposesContinuationMessageAction() {
        compose.setContent {
            DshTheme {
                LocalRunCenterScreen(
                    state = LocalWorkUiState(
                        sessionId = "session-a",
                        jobs = listOf(
                            LocalJobInfo(
                                id = "job-agent",
                                label = "子代理",
                                status = "dormant",
                                ownerSessionId = "session-a",
                                isAgent = true,
                                canMessage = true,
                                continuable = true,
                            ),
                        ),
                    ),
                    onJobOutput = { "" },
                    onArtifacts = { emptyList() },
                    onStopJob = { "" },
                    onStartBackgroundAgent = { LocalWorkUiActionResult(false, "") },
                    onSendAgentMessage = { _, _ -> LocalWorkUiActionResult(false, "") },
                    onOpenResults = {},
                    onDismiss = {},
                )
            }
        }

        compose
            .onNodeWithText(context.getString(R.string.local_run_job_view))
            .performScrollTo()
            .performClick()

        compose
            .onNodeWithText(context.getString(R.string.local_run_agent_dormant_hint))
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(context.getString(R.string.local_run_agent_send))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
