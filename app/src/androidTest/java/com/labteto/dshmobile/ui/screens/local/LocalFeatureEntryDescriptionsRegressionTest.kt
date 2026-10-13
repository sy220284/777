package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalFeatureEntryDescriptionsRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun chatNewSessionShowsOnlyTitlesAndKeepsSelection() {
        var selected: LocalConversationMode? = null
        compose.setContent {
            DshTheme {
                NewSessionModeDialog(
                    usageMode = LocalUsageMode.CHAT,
                    onDismiss = {},
                    onSelect = { selected = it },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.local_new_session_chat_continue_hint)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_new_session_chat_independent_hint)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_new_session_chat_continue)).performClick()
        compose.runOnIdle { assertEquals(LocalConversationMode.CONTINUATION, selected) }
        compose.onNodeWithText(context.getString(R.string.local_new_session_chat_new)).performClick()
        compose.runOnIdle { assertEquals(LocalConversationMode.INDEPENDENT, selected) }
    }

    @Test
    fun workNewSessionHidesAllExplanationsAndKeepsOptions() {
        var selected: LocalConversationMode? = null
        compose.setContent {
            DshTheme {
                NewSessionModeDialog(
                    usageMode = LocalUsageMode.WORK,
                    onDismiss = {},
                    onSelect = { selected = it },
                )
            }
        }
        listOf(
            R.string.local_new_session_dialog_intro,
            R.string.local_new_session_continue_hint,
            R.string.local_new_session_independent_hint,
        ).forEach { resource ->
            compose.onNodeWithText(context.getString(resource)).assertDoesNotExist()
        }
        compose.onNodeWithText(context.getString(R.string.local_new_session_continue)).assertExists()
        compose.onNodeWithText("用当前项目开始新工作").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_new_session_independent)).performClick()
        compose.runOnIdle { assertEquals(LocalConversationMode.INDEPENDENT, selected) }
    }

    @Test
    fun groupNewSessionHasNoExplanatoryTextAndKeepsActions() {
        var selected = ""
        compose.setContent {
            DshTheme {
                GroupNewSessionDialog(
                    onDismiss = {},
                    onNewGroup = { selected = "group" },
                    onNewSingle = { selected = "single" },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.local_group_new_session_intro)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_group_new_group)).performClick()
        compose.runOnIdle { assertEquals("group", selected) }
        compose.onNodeWithText(context.getString(R.string.local_group_new_single)).performClick()
        compose.runOnIdle { assertEquals("single", selected) }
    }

    @Test
    fun agentTeamLaunchEntryHasNoSubtitleAndStillResponds() {
        var clicks = 0
        compose.setContent {
            DshTheme {
                LocalAgentSwarmLaunchEntry(
                    selected = false,
                    onClick = { clicks++ },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.local_team_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.local_team_high_usage_tip)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_team_title)).performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
