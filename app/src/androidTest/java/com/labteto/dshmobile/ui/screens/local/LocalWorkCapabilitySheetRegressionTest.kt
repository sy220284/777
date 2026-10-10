package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityKind
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityReadiness
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityState
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalWorkCapabilitySheetRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun handoffDraftCanBeEditedAndCancelDoesNotStartWork() {
        val prompt = mutableStateOf("old target")
        val summary = mutableStateOf("old summary")
        var entered = 0
        var dismissed = 0
        compose.setContent {
            DshTheme {
                LocalWorkCapabilitySheet(
                    false, false, true, { entered++ }, { dismissed++ },
                    prompt = prompt.value, summary = summary.value,
                    onPromptChange = { prompt.value = it }, onSummaryChange = { summary.value = it },
                )
            }
        }
        compose.onNodeWithText("old target").performTextReplacement("new target")
        compose.onNodeWithText("old summary").performTextReplacement("materials/report.md")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals("new target", prompt.value)
            assertEquals("materials/report.md", summary.value)
            assertEquals(0, entered)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun taskCapabilityReadinessIsVisibleAndDoesNotBlockNormalHandoff() {
        var entered = 0
        compose.setContent {
            DshTheme {
                LocalWorkCapabilitySheet(
                    switching = false, failed = false, enabled = true,
                    onContinue = { entered++ }, onDismiss = {},
                    prompt = "检查 GitHub PR 并联网查询",
                    capabilities = listOf(
                        LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.GITHUB,
                            LocalTaskCapabilityState.CONNECTION_REQUIRED),
                        LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.WEB_SEARCH,
                            LocalTaskCapabilityState.DISABLED),
                    ),
                )
            }
        }
        compose.onNodeWithText("GitHub · 尚未配置连接，可在能力中心设置").assertIsDisplayed()
        compose.onNodeWithText("联网搜索 · 当前已关闭，可在能力中心开启").assertIsDisplayed()
        compose.onNodeWithText("进入工作模式").performClick()
        compose.runOnIdle { assertEquals(1, entered) }
    }

    @Test
    fun enteringWorkRequiresAnExplicitUserAction() {
        var entered = 0
        compose.setContent {
            DshTheme {
                LocalWorkCapabilitySheet(false, false, true, { entered++ }, {})
            }
        }
        compose.runOnIdle { assertEquals(0, entered) }
        compose.onNodeWithText("进入工作模式").performClick()
        compose.runOnIdle { assertEquals(1, entered) }
    }

    @Test
    fun pendingModeTransitionCannotBeSubmittedAgainOrDismissed() {
        compose.setContent {
            DshTheme {
                LocalWorkCapabilitySheet(true, false, true, {}, {})
            }
        }
        compose.onNodeWithText("进入工作模式").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
    }
}
