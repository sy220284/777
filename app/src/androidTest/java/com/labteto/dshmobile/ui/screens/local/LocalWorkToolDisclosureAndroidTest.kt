package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
        compose.onNodeWithText("最新：读取文件内容 · 已完成").assertIsDisplayed()
        compose.onNodeWithTag("work-tool:same").assertIsDisplayed()
        compose.onNodeWithText("当前已经完成第一阶段。").assertIsDisplayed()
    }

    @Test fun newestOperationIsFirstAndCollapsedHeadlineUpdatesWhileRunning() {
        val operations = mutableStateOf<List<LocalTranscriptItem>>(
            listOf(LocalTranscriptItem.WorkPendingTool(LocalToolActivityUiItem(
                callId = "old", name = "read", phase = LocalToolUiPhase.RUNNING,
                declaredSequence = 1,
            )))
        )
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Column(Modifier.width(320.dp)) {
                    LocalWorkToolDisclosureRow(
                        LocalTranscriptItem.WorkTools("live", operations.value),
                        workspacePath = "",
                    )
                }
            }
        }
        compose.onNodeWithText("最新：读取文件内容 · 执行中").assertIsDisplayed()
        compose.runOnIdle {
            operations.value = listOf(
                LocalTranscriptItem.WorkStep(LocalHarnessMessage(
                    "old", "tool", "旧操作完成", createdAt = 1,
                    toolName = "read", toolCallId = "old", toolIsError = false,
                )),
                LocalTranscriptItem.WorkStep(LocalHarnessMessage(
                    "new", "tool", "新操作完成", createdAt = 2,
                    toolName = "edit", toolCallId = "new", toolIsError = false,
                )),
            )
        }
        compose.onNodeWithText("最新：修改文件内容 · 已完成").assertIsDisplayed()
        compose.onNodeWithTag("work-tool-disclosure:live").performClick()
        compose.onNodeWithTag("work-tool-filter:all").assertIsSelected()
        val newY = compose.onNodeWithTag("work-tool:new")
            .fetchSemanticsNode().boundsInRoot.top
        val oldY = compose.onNodeWithTag("work-tool:old")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("展开后的最新操作应在最上方", newY < oldY)
    }

    @Test fun emptyToolResultStillOpensDeclaredArguments() {
        val receipt = LocalHarnessMessage(
            "empty", "tool", "", createdAt = 4,
            toolName = "read", toolCallId = "empty", toolIsError = false,
        )
        val declared = LocalToolActivityUiItem(
            callId = "empty", name = "read", phase = LocalToolUiPhase.COMPLETED,
            argumentsPreview = """{"path":"notes.md"}""",
        )
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Column(Modifier.width(320.dp)) {
                    WorkTimelineStepRow(receipt, activity = declared)
                }
            }
        }
        compose.onNodeWithTag("work-tool:empty").performClick()
        compose.onNodeWithText("""{"path":"notes.md"}""").assertIsDisplayed()
    }

    @Test fun longWorkHistoryIsVirtualizedAndCanReachOldestReceipt() {
        val operations = (1..120).map { n ->
            LocalTranscriptItem.WorkStep(LocalHarnessMessage(
                "t$n", "tool", "结果 $n", createdAt = n.toLong(),
                toolName = "read", toolCallId = "t$n", toolIsError = false,
            ))
        }
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Column(Modifier.width(320.dp)) {
                    LocalWorkToolDisclosureRow(
                        LocalTranscriptItem.WorkTools("many", operations),
                        workspacePath = "",
                    )
                }
            }
        }
        compose.onNodeWithTag("work-tool-disclosure:many").performClick()
        compose.onNodeWithTag("work-tool:t120").assertIsDisplayed()
        compose.onNodeWithTag("work-tool:t1").assertDoesNotExist()
        compose.onNodeWithTag("work-tool-list:many").performScrollToIndex(119)
        compose.onNodeWithTag("work-tool:t1").assertIsDisplayed()
        // Reopening a disclosure starts from the newest receipt, even after deep scrolling.
        compose.onNodeWithTag("work-tool-disclosure:many").performClick()
        compose.onNodeWithTag("work-tool-disclosure:many").performClick()
        compose.onNodeWithTag("work-tool:t120").assertIsDisplayed()
    }

    @Test fun largeTextAndThemeChangesKeepDisclosureAccessibleAcrossMobileWidths() {
        val width = mutableIntStateOf(320)
        val scale = mutableFloatStateOf(1f)
        val theme = mutableStateOf(ThemePreference.LIGHT)
        val operations = listOf(
            LocalTranscriptItem.WorkStep(LocalHarnessMessage(
                "a", "tool", "已有结果", createdAt = 1,
                toolName = "read", toolCallId = "a", toolIsError = false,
            )),
            LocalTranscriptItem.WorkStep(LocalHarnessMessage(
                "b", "tool", "最新结果", createdAt = 2,
                toolName = "edit", toolCallId = "b", toolIsError = false,
            )),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.floatValue)) {
                DshTheme(preference = theme.value) {
                    Column(Modifier.width(width.intValue.dp)) {
                        LocalWorkToolDisclosureRow(
                            LocalTranscriptItem.WorkTools("responsive", operations),
                            workspacePath = "",
                        )
                    }
                }
            }
        }
        val scenarios = listOf(
            Triple(320, 2f, ThemePreference.LIGHT),
            Triple(390, 2f, ThemePreference.DARK),
            Triple(430, 1f, ThemePreference.LIGHT),
        )
        scenarios.forEach { (dp, fontScale, preference) ->
            compose.runOnIdle {
                width.intValue = dp
                scale.floatValue = fontScale
                theme.value = preference
            }
            val header = compose.onNodeWithTag("work-tool-disclosure:responsive")
            header.assertIsDisplayed().assertHasClickAction()
            assertTrue("工作记录在 ${dp}dp 下不能横向溢出",
                header.fetchSemanticsNode().boundsInRoot.width <= dp + 1f)
            compose.onNodeWithText("工具执行记录 · 2 项").assertIsDisplayed()
        }
    }
}
