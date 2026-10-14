package com.labteto.dshmobile.ui.components

import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyPopupMenu
import com.labteto.dshmobile.ui.shenyan.SyMenuItem
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.shenyan.designsystem.components.SySheetChoiceRow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DsBottomSheetRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun reducedViewportReservesFooterSpaceAndKeepsBodyActionsReachable() {
        var selected = 0
        var saved = 0
        compose.setContent {
            DshTheme {
                SyResponsiveSheet(
                    title = "交接",
                    onDismiss = {},
                    modifier = Modifier.heightIn(max = 360.dp),
                    scrollable = true,
                    footer = { SyButton(text = "继续工作", onClick = { saved++ }, modifier = Modifier.fillMaxWidth()) },
                ) {
                    repeat(30) { Text("历史消息 $it") }
                    SySheetChoiceRow(title = "选择末条消息", onClick = { selected++ })
                }
            }
        }
        compose.onNodeWithTag("bottomSheetScrollBody").assertIsDisplayed()
        compose.onNodeWithText("继续工作").assertIsDisplayed().performClick()
        compose.onNodeWithText("选择末条消息").performScrollTo().assertIsDisplayed().performClick()
        // The pinned action must still be in the viewport after scrolling the body.
        compose.onNodeWithText("继续工作").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, selected)
            assertEquals(1, saved)
        }
    }

    @Test
    fun longSheetKeepsSaveActionVisibleAndClickable() {
        var saved = 0
        compose.setContent {
            DshTheme {
                SyResponsiveSheet(
                    title = "人物编辑",
                    onDismiss = {},
                    scrollable = true,
                    footer = {
                        SyButton(
                            text = "保存更改",
                            onClick = { saved++ },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                ) {
                    repeat(65) { Text("人物参数 $it") }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("bottomSheetScrollBody").assertExists()
        compose.onNodeWithText("保存更改").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, saved) }
    }

    @Test
    fun seamlessAttachmentSheetKeepsActionClickableWithoutMaterialBackdrop() {
        var selected = 0
        compose.setContent {
            DshTheme {
                SyResponsiveSheet(
                    title = null,
                    onDismiss = {},
                    showDragHandle = false,
                    seamlessBackground = true,
                ) {
                    SySheetChoiceRow(title = "团队协作", onClick = { selected++ })
                }
            }
        }
        compose.onNodeWithTag("sySceneBackgroundSurface").assertIsDisplayed()
        val panel = compose.onNodeWithTag("sySceneBackgroundSurface").getUnclippedBoundsInRoot()
        assertTrue("Background painter must not expand the compact menu", (panel.bottom - panel.top).value < 280f)
        compose.onNodeWithText("团队协作").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, selected) }
    }

    @Test
    fun seamlessSuggestionPopupKeepsActionsAccessible() {
        var selected = 0
        compose.setContent {
            DshTheme {
                Box {
                    SyButton(text = "试试这些", onClick = {})
                    SyPopupMenu(
                        expanded = true,
                        onDismiss = {},
                        seamlessBackground = true,
                        items = listOf(SyMenuItem("联网调研", onClick = { selected++ })),
                    )
                }
            }
        }
        compose.onNodeWithTag("sySeamlessPopupScene").assertIsDisplayed()
        compose.onNodeWithText("联网调研").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, selected) }
    }

    @Test
    fun defaultPopupMenuUsesContinuousBackground() {
        var clicked = 0
        compose.setContent {
            DshTheme {
                Box {
                    SyPopupMenu(
                        expanded = true,
                        onDismiss = {},
                        items = listOf(SyMenuItem("编辑任务", onClick = { clicked++ })),
                    )
                }
            }
        }
        compose.onNodeWithTag("sySeamlessPopupScene").assertIsDisplayed()
        compose.onNodeWithText("编辑任务").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicked) }
    }

    @Test
    fun seamlessPersonaPickerKeepsHeaderAndContentsVisible() {
        compose.setContent {
            DshTheme {
                SyResponsiveSheet(
                    title = "选择人物",
                    subtitle = "点选头像，开始一段新故事。",
                    onDismiss = {},
                    seamlessBackground = true,
                ) {
                    Text("人物头像")
                }
            }
        }
        compose.onNodeWithTag("sySceneBackgroundSurface").assertIsDisplayed()
        compose.onNodeWithText("选择人物").assertIsDisplayed()
        compose.onNodeWithText("人物头像").assertIsDisplayed()
    }

    @Test
    fun shortChoiceSheetUsesFullRowAsActionTarget() {
        var selected = 0
        compose.setContent {
            DshTheme {
                SyResponsiveSheet(title = "选择会话", onDismiss = {}) {
                    SySheetChoiceRow(
                        title = "独立会话",
                        subtitle = "从空白上下文开始",
                        onClick = { selected++ },
                    )
                }
            }
        }
        compose.onNodeWithText("独立会话").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, selected) }
    }
}
