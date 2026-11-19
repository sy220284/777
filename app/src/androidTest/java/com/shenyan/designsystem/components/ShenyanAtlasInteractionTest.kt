package com.shenyan.designsystem.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shenyan.designsystem.foundation.ShenyanTheme
import com.shenyan.designsystem.foundation.SyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ShenyanAtlasInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun multiSelectionCancelKeepsExternalStateUnchanged() {
        var closed by mutableStateOf(false)
        var applied: Set<String>? = null
        compose.setContent {
            ShenyanTheme {
                if (!closed) SyMultiSelectSheet(
                    title = "选择标签",
                    options = listOf(SyOption("a", "设计"), SyOption("b", "动效")),
                    selectedIds = setOf("a"),
                    onConfirm = { applied = it; closed = true },
                    onDismiss = { closed = true },
                )
            }
        }
        compose.onNodeWithText("动效").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals(true, closed)
            assertNull(applied)
        }
    }

    @Test fun multiSelectionCommitsExactlyOneConfirmedSet() {
        var closed by mutableStateOf(false)
        var applied: Set<String>? = null
        var count = 0
        compose.setContent {
            ShenyanTheme {
                if (!closed) SyMultiSelectSheet(
                    title = "选择标签",
                    options = listOf(SyOption("a", "设计"), SyOption("b", "动效")),
                    selectedIds = setOf("a"),
                    onConfirm = { result -> applied = result; count++; closed = true },
                    onDismiss = { closed = true },
                )
            }
        }
        compose.onNodeWithText("动效").performClick()
        compose.onNodeWithText("应用（2）").performClick()
        compose.runOnIdle {
            assertEquals(setOf("a", "b"), applied)
            assertEquals(1, count)
        }
    }

    @Test fun coachMarkUsesRealSpotlightAndDismisses() {
        var expanded by mutableStateOf(false)
        compose.setContent {
            ShenyanTheme {
                SyCoachMark(
                    title = "操作引导",
                    description = "检查工作权限",
                    expanded = expanded,
                    onDismiss = { expanded = false },
                ) {
                    SyButton("查看引导", { expanded = true })
                }
            }
        }
        compose.onNodeWithText("查看引导").performClick()
        compose.onNodeWithTag("sy-coach-spotlight").assertExists()
        compose.onNodeWithText("知道了").performClick()
        compose.runOnIdle { assertEquals(false, expanded) }
    }

    @Test fun destructiveActionNeedsExplicitConfirmation() {
        var chosen: String? = null
        var closed by mutableStateOf(false)
        compose.setContent {
            ShenyanTheme {
                if (!closed) SyActionSheet(
                    title = "更多",
                    entries = listOf(SyActionEntry("delete", "删除样例", destructive = true)),
                    onChoose = { chosen = it },
                    onDismiss = { closed = true },
                )
            }
        }
        compose.onNodeWithText("删除样例").performClick()
        compose.runOnIdle { assertNull(chosen) }
        compose.onNodeWithText("确认执行").performClick()
        compose.runOnIdle { assertEquals("delete", chosen) }
    }
}
