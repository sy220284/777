package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.shenyan.SyContextActionMenu
import com.labteto.dshmobile.ui.shenyan.SyPopupMenu
import com.labteto.dshmobile.ui.shenyan.SyMenuItem
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test

/**
 * Candidate reproduction for historical Compose "layouts are not part of the same hierarchy".
 * The old diagnostic has no stack frames, so this deliberately does not claim the root cause.
 * Stress selection anchors while the message composition and an independent popup window change.
 */
class LocalTextSelectionPopupHierarchyRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectedMarkdownDoesNotKeepDetachedCoordinatesAcrossContextPopupAndMessageReplacement() {
        var visible by mutableStateOf(true)
        var message by mutableStateOf("初始段落：长按这段文本")
        var open by mutableStateOf(false)
        compose.setContent {
            DshTheme {
                Column {
                    if (visible) SelectionContainer { MarkdownText(message) }
                    SyContextActionMenu(
                        expanded = open, onDismiss = { open = false },
                        items = listOf(SyMenuItem("复制", onClick = { open = false })),
                    )
                }
            }
        }
        compose.onNodeWithText("初始段落：长按这段文本").performTouchInput { longClick() }
        compose.runOnIdle {
            open = true
            message = "新段落：替换后的文本"
        }
        compose.waitForIdle()
        compose.runOnIdle {
            visible = false
            open = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            visible = true
            message = "再次显示的段落"
        }
        compose.onNodeWithText("再次显示的段落").assertExists()
    }

    @Test
    fun selectionAndDropdownBelongToIndependentComposeWindowsDuringStreamingUpdates() {
        var text by mutableStateOf("正在生成的回答")
        var open by mutableStateOf(false)
        compose.setContent {
            DshTheme {
                Column {
                    SelectionContainer { Text(text) }
                    Box {
                        SyPopupMenu(
                            expanded = open, onDismiss = { open = false },
                            items = listOf(SyMenuItem("更多操作", onClick = { open = false })),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("正在生成的回答").performTouchInput { longClick() }
        compose.runOnIdle {
            open = true
            text = "正在生成的回答，新增加了一段内容"
        }
        compose.waitForIdle()
        compose.runOnIdle {
            open = false
            text = "完整回答"
        }
        compose.onNodeWithText("完整回答").assertExists()
    }
}
