package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Check the actual narrow-screen disclosure, preserving independent public milestones. */
class LocalWorkToolDisclosureAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun operationsStartCollapsedWhileProgressAndFinalRemainReadable() {
        val events = listOf(
            LocalHarnessMessage("user", "user", "检查项目", createdAt = 1),
            LocalHarnessMessage("progress-1", "progress", "正在核对实现。", createdAt = 2),
            LocalHarnessMessage("read", "tool", "文件读取成功", createdAt = 3,
                toolName = "read", toolCallId = "read", toolIsError = false),
            LocalHarnessMessage("progress-2", "progress", "已找到问题，开始修改。", createdAt = 4),
            LocalHarnessMessage("answer", "assistant", "最终结论：修改完成。", createdAt = 5),
        )
        val timeline = foldWorkToolsToTurnStart(buildLocalTranscript(events))
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Column(Modifier.width(320.dp)) {
                    timeline.forEach { item ->
                        when (item) {
                            is LocalTranscriptItem.WorkTools ->
                                LocalWorkToolDisclosureRow(item, workspacePath = "")
                            is LocalTranscriptItem.WorkStep ->
                                WorkTimelineStepRow(item.message)
                            is LocalTranscriptItem.Message -> Text(item.message.content)
                            else -> Unit
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("工具执行记录 · 1 项").assertIsDisplayed()
        compose.onNodeWithText("正在核对实现。").assertIsDisplayed()
        compose.onNodeWithText("已找到问题，开始修改。").assertIsDisplayed()
        compose.onNodeWithText("最终结论：修改完成。").assertIsDisplayed()
        compose.onNodeWithText("文件读取成功").assertDoesNotExist()
        compose.onNodeWithTag("work-tool:read").assertDoesNotExist()

        val headerY = compose.onNodeWithTag("work-tool-disclosure:user")
            .fetchSemanticsNode().boundsInRoot.top
        val progressY = compose.onNodeWithText("正在核对实现。")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("工具记录应位于本轮阶段性正文前", headerY < progressY)

        compose.onNodeWithTag("work-tool-disclosure:user").performClick()
        compose.onNodeWithTag("work-tool:read").assertIsDisplayed().performClick()
        compose.onNodeWithText("文件读取成功").assertIsDisplayed()
    }

    @Test fun pendingToolCanSettleWithoutResettingExpandedDisclosure() {
        val pending = LocalToolActivityUiItem(
            callId = "same", name = "read", phase = LocalToolUiPhase.RUNNING,
            argumentsPreview = """{"path":"doc.md"}""", declaredSequence = 3,
        )
        val operations = mutableStateOf<List<LocalTranscriptItem>>(
            listOf(LocalTranscriptItem.WorkPendingTool(pending)))
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Column(Modifier.width(320.dp)) {
                    LocalWorkToolDisclosureRow(
                        LocalTranscriptItem.WorkTools("turn", operations.value),
                        workspacePath = "",
                    )
                    Text("当前已经完成第一阶段。")
                }
            }
        }
        compose.onNodeWithText("执行中 1").assertIsDisplayed()
        compose.onNodeWithTag("work-tool-disclosure:turn").performClick()
        compose.onNodeWithText("读取文件内容").assertIsDisplayed()

        compose.runOnIdle {
            operations.value = listOf(LocalTranscriptItem.WorkStep(
                LocalHarnessMessage("read", "tool", "已读取 doc.md", createdAt = 4,
                    toolName = "read", toolCallId = "same", toolIsError = false)))
        }
        compose.onNodeWithText("已完成").assertIsDisplayed()
        compose.onNodeWithTag("work-tool:same").assertIsDisplayed()
        compose.onNodeWithText("当前已经完成第一阶段。").assertIsDisplayed()
    }
}
