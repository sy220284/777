package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalMessageMemorySourceActionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun chatUserMessageMenuCanOpenExactSourceWithoutMutatingOriginalMessage() {
        var sourceId: String? = null
        var edited = 0
        val original = LocalHarnessMessage(
            id = "user-message-23", role = "user",
            content = "周末我们约好了去公园", createdAt = 1L,
        )
        compose.setContent {
            DshTheme {
                LocalMessageRow(
                    message = original, chatMode = true, groupMode = false,
                    canEdit = true, canRegenerate = false, canSelectVariant = false,
                    branchInfo = null,
                    onEdit = { edited++ },
                    onSelectVariant = { _, _ -> false },
                    onRegenerate = { false },
                    onLocateSourceMemory = { sourceId = it.id },
                )
            }
        }
        compose.onNodeWithText("周末我们约好了去公园", substring = true)
            .performTouchInput { longClick() }
        compose.onNodeWithText("定位并纠正相关记忆").performClick()
        compose.runOnIdle {
            assertEquals("user-message-23", sourceId)
            assertEquals(0, edited)
            assertEquals("周末我们约好了去公园", original.content)
        }
    }

    @Test
    fun workModeDoesNotExposeCharacterMemoryMenu() {
        val original = LocalHarnessMessage(
            id = "work-msg-1", role = "user", content = "运行构建", createdAt = 1L,
        )
        compose.setContent {
            DshTheme {
                LocalMessageRow(
                    message = original, chatMode = false, groupMode = false,
                    canEdit = true, canRegenerate = false, canSelectVariant = false,
                    branchInfo = null, onEdit = {}, onSelectVariant = { _, _ -> false },
                    onRegenerate = { false },
                    onLocateSourceMemory = {},
                )
            }
        }
        compose.onNodeWithText("运行构建", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("定位并纠正相关记忆").assertDoesNotExist()
    }
}
