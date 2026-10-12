package com.labteto.dshmobile.ui.components

import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.shenyan.designsystem.components.SySheetChoiceRow

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DsBottomSheetRegressionTest {
    @get:Rule val compose = createComposeRule()

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
