package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalHarnessStreamingState
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/** Android regression of the single-surface GPT-style narration/tool interleave. */
class WorkConversationFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun liveTimelineCollapsesToolRowsIntoNarratedMilestones() {
        val running = mutableStateOf(true)
        val events = listOf(
            LocalHarnessMessage("p1", "progress", "我先检查当前消息流。", createdAt = 1L),
            LocalHarnessMessage("t1", "tool", "/private/args/secret",
                toolName = "mcp__GitHub__fetch", createdAt = 2L),
            LocalHarnessMessage("p2", "progress", "已经定位问题，正在更新实现。", createdAt = 3L),
            LocalHarnessMessage("t2", "tool", "internal tool output",
                toolName = "mcp__Figma__use_figma", createdAt = 4L),
        )
        compose.setContent { DshTheme { WorkProcessRow(events, running.value) } }
        compose.onNodeWithText("我先检查当前消息流。").assertExists()
        compose.onNodeWithText("GitHub", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已经定位问题，正在更新实现。").assertExists()
        compose.onNodeWithText("Figma", substring = true).assertDoesNotExist()
        compose.onNodeWithText("/private/args/secret", substring = true).assertDoesNotExist()
        compose.onNodeWithText("internal tool output", substring = true).assertDoesNotExist()

        compose.runOnIdle { running.value = false }
        compose.waitForIdle()
        // Completed work folds back to a lightweight one-line status; the final assistant
        // answer is rendered independently by the existing transcript message component.
        compose.onNodeWithText("GitHub", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Figma", substring = true).assertDoesNotExist()
    }

    @Test fun liveAssistantTextAppearsAfterDurableStepsWithoutPrivateReasoning() {
        val stream = MutableStateFlow(LocalHarnessStreamingState(
            sessionId = "s1",
            requestId = "r1",
            usageMode = LocalUsageMode.WORK,
            assistant = "现在继续验证修复结果。",
            reasoning = "private hidden reasoning",
        ))
        compose.setContent {
            DshTheme {
                LocalStreamingWorkPreview(
                    sessionId = "s1",
                    streamingState = stream,
                    hasDurableProgress = true,
                    lastDurableNarrative = "我先检查当前消息流。",
                )
            }
        }
        compose.onNodeWithText("现在继续验证修复结果。").assertExists()
        compose.onNodeWithText("private hidden reasoning", substring = true).assertDoesNotExist()
        stream.value = stream.value.copy(assistant = "我先检查当前消息流。")
        compose.waitForIdle()
        compose.onNodeWithText("我先检查当前消息流。").assertDoesNotExist()
    }
}
