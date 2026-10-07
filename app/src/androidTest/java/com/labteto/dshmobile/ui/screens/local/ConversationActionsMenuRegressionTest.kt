package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationActionsMenuRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sessionActionsStayAnchoredAndInvokeTheSelectedAction() {
        var renamed = 0
        var pinned = 0
        var deleted = 0
        compose.setContent {
            DshTheme {
                ConversationActionsMenu(
                    pinned = false,
                    onTogglePin = { pinned++ },
                    onRename = { renamed++ },
                    onDelete = { deleted++ },
                )
            }
        }

        compose.onNodeWithContentDescription("对话操作").performClick()
        compose.onNodeWithText("重命名").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, renamed)
            assertEquals(0, pinned)
            assertEquals(0, deleted)
        }

        compose.onNodeWithContentDescription("对话操作").performClick()
        compose.onNodeWithText("置顶").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, pinned) }
    }
}
