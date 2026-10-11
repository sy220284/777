package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalHarnessStreamingState
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/** Android regression for the always-visible Work operation stream. */
class WorkConversationFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun liveTimelineShowsEveryMilestoneWithoutExpandingOrLeakingToolResults() {
        val running = mutableStateOf(true)
        val events = listOf(
            LocalHarnessMessage("p1", "progress", "我先检查当前消息流。", createdAt = 1L),
            LocalHarnessMessage("t1", "tool", "/internal/test/arguments",
                toolName = "mcp__GitHub__fetch", createdAt = 2L),
            LocalHarnessMessage("p2", "progress", "已经定位问题，正在更新实现。", createdAt = 3L),
            LocalHarnessMessage("t2", "tool", "internal tool output",
                toolName = "mcp__Figma__use_figma", createdAt = 4L),
        )
        compose.setContent { DshTheme { WorkProcessRow(events, running.value) } }
        compose.onNodeWithText("GitHub · 我先检查当前消息流。").assertExists()
        compose.onNodeWithText("Figma · 已经定位问题，正在更新实现。").assertExists()
        compose.onNodeWithText("工作进行中").assertDoesNotExist()
        compose.onNodeWithText("展开工作过程").assertDoesNotExist()
        compose.onNodeWithText("/internal/test/arguments", substring = true).assertDoesNotExist()
        compose.onNodeWithText("internal tool output", substring = true).assertDoesNotExist()

        compose.runOnIdle { running.value = false }
        compose.waitForIdle()
        // Both steps remain visible after completion; the final assistant response is separate.
        compose.onNodeWithText("GitHub · 我先检查当前消息流。").assertExists()
        compose.onNodeWithText("Figma · 已经定位问题，正在更新实现。").assertExists()
    }

    @Test fun progressNarrationCanShowASeparateBlockerExplanation() {
        val events = listOf(
            LocalHarnessMessage("p1", "progress",
                "已经定位一个需要处理的关键阻塞问题。网络连接已断开，正在确认是否可以安全恢复。",
                createdAt = 1L),
            LocalHarnessMessage("t1", "tool", "private failure details",
                toolName = "mcp__GitHub__fetch", toolIsError = true, createdAt = 2L),
        )
        compose.setContent { DshTheme { WorkProcessRow(events, running = false) } }
        compose.onNodeWithText("GitHub · 已经定位一个需要处理的关键阻塞问题。").assertExists()
        compose.onNodeWithText("网络连接已断开，正在确认是否可以安全恢复。").assertExists()
        compose.onNodeWithText("private failure details", substring = true).assertDoesNotExist()
    }

    @Test fun longTimelineKeepsEarlyStepsVisibleInsteadOfShowingOnlyFive() {
        val events = (1..9).map { index ->
            LocalHarnessMessage("p$index", "progress", "阶段$index 的验证已结束", createdAt = index.toLong())
        }
        compose.setContent { DshTheme { WorkProcessRow(events, running = false) } }
        compose.onNodeWithText("阶段1 的验证已结束").assertExists()
        compose.onNodeWithText("阶段9 的验证已结束").assertExists()
        compose.onAllNodesWithText("阶段", substring = true).assertCountEquals(9)
    }

    @Test fun liveAssistantTextAppearsAfterDurableStepsWithoutPrivateReasoning() {
        val stream = MutableStateFlow(LocalHarnessStreamingState(
            sessionId = "s1",
            requestId = "r1",
            usageMode = LocalUsageMode.WORK,
            assistant = "现在继续验证修复结果。",
            reasoning = "internal reasoning text",
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
        compose.onNodeWithText("internal reasoning text", substring = true).assertDoesNotExist()
        stream.value = stream.value.copy(assistant = "我先检查当前消息流。")
        compose.waitForIdle()
        compose.onNodeWithText("我先检查当前消息流。").assertDoesNotExist()
    }
    @Test fun workDisplayRendersSeparateStepsAndMilestones() {
        val durable = LocalTranscriptItem.WorkProcess(listOf(
            LocalHarnessMessage("p1", "progress", "开始检查当前配置", createdAt = 1L),
            LocalHarnessMessage("t1", "tool", "/private/output",
                toolName = "mcp__GitHub__fetch_file", toolIsError = false, createdAt = 2L),
            LocalHarnessMessage("p2", "progress", "发现两个需要处理的问题。", createdAt = 3L),
            LocalHarnessMessage("t2", "tool", "private error",
                toolName = "mcp__Figma__use_figma", toolIsError = true, createdAt = 4L),
        ))
        val steps = buildLocalDisplayTranscript(listOf(durable))
            .filterIsInstance<LocalTranscriptItem.WorkStep>()
        compose.setContent {
            DshTheme {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(steps.size) { index ->
                        val step = steps[index]
                        WorkTimelineStepRow(step.message, step.repeatCount)
                    }
                }
            }
        }
        compose.onNodeWithText("开始检查当前配置").assertExists()
        compose.onNodeWithText("GitHub ·", substring = true).assertExists()
        compose.onNodeWithText("发现两个需要处理的问题。").assertExists()
        compose.onNodeWithText("private error", substring = true).assertDoesNotExist()
        compose.onNodeWithText("/private/output", substring = true).assertDoesNotExist()
    }

}
