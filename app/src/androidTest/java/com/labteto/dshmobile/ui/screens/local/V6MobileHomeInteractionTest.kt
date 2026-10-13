package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Test real Compose V6 destinations and the selected message-to-Work handoff. */
class V6MobileHomeInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chatTabDestinationsUseTheExistingSessionAndPersonaActions() {
        var destination = ""
        compose.setContent {
            DshTheme {
                V6MobileHomeNavigation(
                    mode = LocalUsageMode.CHAT,
                    onSessions = { destination = "sessions" },
                    onPersona = { destination = "persona" },
                    onWorkspace = { destination = "files" },
                )
            }
        }
        compose.onNodeWithTag("v6MobileHomeNavigation:0").performClick()
        compose.runOnIdle { assertEquals("sessions", destination) }
        compose.onNodeWithTag("v6MobileHomeNavigation:2").performClick()
        compose.runOnIdle { assertEquals("persona", destination) }
        compose.onNodeWithTag("v6MobileHomeNavigation:1").performClick()
        compose.runOnIdle { assertEquals("persona", destination) } // active page is a no-op
    }

    @Test fun workTabOpensRealWorkspaceActionInsteadOfPersona() {
        var route = ""
        compose.setContent {
            DshTheme {
                V6MobileHomeNavigation(
                    mode = LocalUsageMode.WORK,
                    onSessions = { route = "sessions" },
                    onPersona = { route = "persona" },
                    onWorkspace = { route = "files" },
                )
            }
        }
        compose.onNodeWithTag("v6MobileHomeNavigation:2").performClick()
        compose.runOnIdle { assertEquals("files", route) }
    }

    @Test fun selectedUserMessageOpensTheRealWorkHandoffWithExactText() {
        val message = LocalHarnessMessage(
            id = "user-selected-67", role = "user",
            content = "把最近的研究整理成工作计划", createdAt = 1L,
        )
        var selected: LocalHarnessMessage? = null
        compose.setContent {
            DshTheme {
                LocalMessageRow(
                    message = message, chatMode = true, groupMode = false,
                    canEdit = true, canRegenerate = false, canSelectVariant = false,
                    branchInfo = null, onEdit = {}, onSelectVariant = { _, _ -> false },
                    onRegenerate = { false },
                    onConvertToWork = { selected = it },
                )
            }
        }
        compose.onNodeWithText("把最近的研究整理成工作计划", substring = true)
            .performTouchInput { longClick() }
        compose.onNodeWithText("转为任务").performClick()
        compose.runOnIdle {
            assertEquals("user-selected-67", selected?.id)
            assertEquals("把最近的研究整理成工作计划", selected?.content)
            assertEquals("把最近的研究整理成工作计划", message.content)
        }
        compose.onNodeWithText("转为任务").assertDoesNotExist()
    }

    @Test fun workMessageMustNotExposeTheChatOnlyHandoffAction() {
        val message = LocalHarnessMessage(
            id = "work-selected", role = "user", content = "完成构建", createdAt = 1L,
        )
        compose.setContent {
            DshTheme {
                LocalMessageRow(
                    message = message, chatMode = false, groupMode = false,
                    canEdit = true, canRegenerate = false, canSelectVariant = false,
                    branchInfo = null, onEdit = {}, onSelectVariant = { _, _ -> false },
                    onRegenerate = { false }, onConvertToWork = {},
                )
            }
        }
        compose.onNodeWithText("完成构建", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("转为任务").assertDoesNotExist()
    }
}
