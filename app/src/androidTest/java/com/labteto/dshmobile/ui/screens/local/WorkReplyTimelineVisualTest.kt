package com.labteto.dshmobile.ui.screens.local

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.shenyan.designsystem.foundation.LocalSyColors
import java.io.File
import org.junit.Rule
import org.junit.Test

class WorkReplyTimelineVisualTest {
    @Test fun waitingForNextModelStepRemainsVisibleAfterToolProgress() {
        compose.setContent { DshTheme { LocalStreamingWorkPreview(
            sessionId = "work",
            streamingState = kotlinx.coroutines.flow.MutableStateFlow(
                com.labteto.dshmobile.local.model.LocalHarnessStreamingState()),
        ) } }
        compose.onNodeWithText("正在准备下一步…").assertIsDisplayed()
    }
    @get:Rule val compose = createComposeRule()

    private val result = LocalHarnessMessage("result", "tool", "文件内容已读取：发现两处配置差异。",
        "mcp__github__fetch_file", 2L, toolCallId = "read-1", toolIsError = false)

    @Composable private fun Timeline() {
        Column(Modifier.width(320.dp).background(LocalSyColors.current.canvas).padding(16.dp)) {
            WorkTimelineStepRow(LocalHarnessMessage("note", "progress",
                "## 核查结果\n\n已找到 **两处配置差异**，正在继续验证。\n\n- 保留现有文件\n- 核对修改内容",
                createdAt = 1L))
            WorkTimelineStepRow(result)
            WorkTimelineStepRow(result.copy(id = "failed", toolCallId = "failed-1",
                toolName = "bash", toolIsError = true, toolErrorCode = "NETWORK_ERROR"))
            WorkTimelineStepRow(result.copy(id = "unknown", toolCallId = "unknown-1",
                toolName = "write", toolIsError = true, toolErrorCode = "TOOL_OUTCOME_UNKNOWN"))
            WorkPendingToolRow(LocalToolActivityUiItem("pending", "mcp__github__get_pr_info", LocalToolUiPhase.RUNNING))
        }
    }

    @Test fun narrowTimelineKeepsMarkdownStatusAndOnDemandDetails() {
        compose.setContent { DshTheme(preference = ThemePreference.LIGHT) { Timeline() } }
        compose.onNodeWithText("核查结果").assertIsDisplayed()
        compose.onNodeWithText("## 核查结果", substring = true).assertDoesNotExist()
        compose.onNodeWithText(result.content).assertDoesNotExist()
        saveScreenshot("light-320dp")
        compose.onNodeWithTag("work-tool:read-1").performClick()
        compose.onNodeWithText(result.content).assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText(result.content).assertDoesNotExist()
    }

    @Test fun commandToolShowsNaturalActionWithReceiptAvailable() {
        val activity = LocalToolActivityUiItem(
            "check-curl", "bash", LocalToolUiPhase.COMPLETED,
            argumentsPreview = """{"command":"command -v curl"}""",
        )
        val command = result.copy(id = "check-curl", toolCallId = "check-curl",
            toolName = "bash", content = "curl 可用")
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Column(Modifier.width(320.dp)) {
                    WorkTimelineStepRow(command, activity = activity)
                }
            }
        }
        compose.onNodeWithText("检查 curl 是否可用").assertIsDisplayed()
        compose.onNodeWithText("执行命令").assertDoesNotExist()
        compose.onNodeWithTag("work-tool:check-curl").performClick()
        compose.onNodeWithText("curl 可用").assertIsDisplayed()
    }

    @Test fun chatStyleTimelineSeparatesNarrationAndSemanticActions() {
        val search = LocalToolActivityUiItem(
            "find-skill", "mcp__GitHub__search", LocalToolUiPhase.COMPLETED,
            argumentsPreview = """{"query":"aesthetic-ui design skills"}""",
        )
        val change = LocalToolActivityUiItem(
            "edit-ui", "mcp__GitHub__update_file", LocalToolUiPhase.RUNNING,
            argumentsPreview = """{"path":"app/src/ui/screens/Message.kt"}""",
        )
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Column(Modifier.width(320.dp)
                    .background(LocalSyColors.current.canvas).padding(16.dp)) {
                    WorkTimelineStepRow(LocalHarnessMessage(
                        "intro", "progress", "我先核对现有界面与设计规范。", createdAt = 1L))
                    WorkTimelineStepRow(LocalHarnessMessage(
                        "authored", "progress", "检索技能配置与仓库测试文件", createdAt = 1L))
                    WorkTimelineStepRow(LocalHarnessMessage("step1", "tool", "搜索完成",
                        "mcp__GitHub__search", createdAt = 2L,
                        toolCallId = "find-skill", toolIsError = false), activity = search)
                    WorkTimelineStepRow(LocalHarnessMessage(
                        "detail", "progress", "## 核查结果\n\n已经定位需要调整的步骤区域。",
                        createdAt = 3L))
                    WorkPendingToolRow(change)
                }
            }
        }
        compose.onNodeWithText("检索视觉与 UI 设计技能").assertIsDisplayed()
        compose.onNodeWithText("检索技能配置与仓库测试文件").assertIsDisplayed()
        compose.onNodeWithText("修改界面代码", substring = true).assertIsDisplayed()
        compose.onNodeWithText("核查结果").assertIsDisplayed()
        compose.onNodeWithText("mcp__GitHub__search", substring = true).assertDoesNotExist()
        saveScreenshot("chat-style-semantic-work-320dp")
    }

    @Test fun darkLargeTextKeepsFailureAndRunningStatusReadable() {
        compose.setContent { DshTheme(preference = ThemePreference.DARK, textScale = 1.3f) { Timeline() } }
        compose.onNodeWithText("结果待确认").assertIsDisplayed()
        compose.onNodeWithText("执行中").assertIsDisplayed()
        saveScreenshot("dark-320dp-large-text")
    }

    @Test fun artifactReadFailureClearlyExplainsRestorationWithoutHidingOldResults() {
        compose.setContent {
            DshTheme {
                WorkToolStatusNotice("成果文件状态读取失败，正在尝试恢复；已保存的成果列表仍可查看")
            }
        }
        compose.onNodeWithText("成果文件状态读取失败，正在尝试恢复；已保存的成果列表仍可查看")
            .assertIsDisplayed()
    }

    @Test fun statusFailureExplainsAutomaticRecoveryWithoutManualAction() {
        compose.setContent { DshTheme { WorkToolStatusNotice() } }
        compose.onNodeWithText("工具状态同步暂不可用，正在自动恢复；已保存的回复仍可查看").assertIsDisplayed()
        compose.onNodeWithText("重试状态读取").assertDoesNotExist()
    }

    private fun saveScreenshot(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "work-timeline-review")
        check(directory.exists() || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
