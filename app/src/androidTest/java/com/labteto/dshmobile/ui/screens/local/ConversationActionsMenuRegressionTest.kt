package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
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

        compose.onNodeWithContentDescription("对话操作").performClick()
        compose.onNode(hasText("删除会话") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(0, deleted) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, deleted) }

        compose.onNodeWithContentDescription("对话操作").performClick()
        compose.onNode(hasText("删除会话") and hasClickAction()).performClick()
        // The dialog surface and its confirm button both expose the title in merged semantics.
        // Select the actionable confirmation node, not the outer scroll container.
        compose.onAllNodes(hasText("删除会话") and hasClickAction()).onLast().performClick()
        compose.runOnIdle { assertEquals(1, deleted) }
    }

    @Test
    fun deletionConfirmationKeepsOriginalSessionTargetAfterRecomposition() {
        var currentSession by mutableStateOf("original")
        var deletedSession: String? = null
        compose.setContent {
            val sessionAtComposition = currentSession
            DshTheme {
                ConversationActionsMenu(
                    pinned = false,
                    onTogglePin = {},
                    onRename = {},
                    onDelete = { deletedSession = sessionAtComposition },
                )
            }
        }

        compose.onNodeWithContentDescription("对话操作").performClick()
        compose.onNode(hasText("删除会话") and hasClickAction()).performClick()
        compose.runOnIdle { currentSession = "different" }
        compose.onAllNodes(hasText("删除会话") and hasClickAction()).onLast().performClick()
        compose.runOnIdle { assertEquals("original", deletedSession) }
    }
}
