package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalDrawerEntryVisibilityRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun workDrawerDisplaysEntriesDirectlyWithoutExtraExpandButton() {
        var openToolsCount = 0
        compose.setContent {
            DshTheme {
                DrawerQuickActions(
                    usageMode = LocalUsageMode.WORK,
                    groupMemberCount = 0,
                    galleryCount = 0,
                    onOpenGroupChat = {},
                    onOpenPersonaGallery = {},
                    onOpenDiary = {},
                    onTasks = {},
                    onWorkspaceFiles = {},
                    onProjects = {},
                    onOpenRunCenter = {},
                    onTools = { openToolsCount++ },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.app_extended_capabilities)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_project_management_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.local_run_center)).assertExists()
        compose.onNodeWithText(context.getString(R.string.chatlist_workspace_files)).assertExists()
        compose.onNodeWithText(context.getString(R.string.tasks_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.tools_title)).performClick()
        compose.runOnIdle { assertEquals(1, openToolsCount) }
    }
    @Test
    fun chatDrawerHidesProjectManagementAndKeepsChatActions() {
        compose.setContent {
            DshTheme {
                DrawerQuickActions(
                    usageMode = LocalUsageMode.CHAT,
                    groupMemberCount = 0,
                    galleryCount = 0,
                    onOpenGroupChat = {},
                    onOpenPersonaGallery = {},
                    onOpenDiary = {},
                    onTasks = {},
                    onWorkspaceFiles = {},
                    onProjects = {},
                    onOpenRunCenter = {},
                    onTools = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.local_project_management_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.persona_gallery_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.local_group_chat_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.tasks_chat_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.chat_diary_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.local_run_center)).assertDoesNotExist()
    }
    @Test
    fun quotaEntryAppearsInBothModesAndDisappearsWithoutMiniMaxAction() {
        var opens = 0
        val mode = androidx.compose.runtime.mutableStateOf(LocalUsageMode.WORK)
        val available = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            DshTheme {
                DrawerQuickActions(
                    usageMode = mode.value, groupMemberCount = 0, galleryCount = 0,
                    onOpenGroupChat = {}, onOpenPersonaGallery = {}, onOpenDiary = {},
                    onTasks = {}, onWorkspaceFiles = {}, onProjects = {}, onOpenRunCenter = {},
                    onTools = {}, onMiniMaxQuota = if (available.value) { { opens++ } } else null,
                )
            }
        }
        val title = context.getString(R.string.minimax_quota_title)
        compose.onNodeWithText(title).performClick()
        compose.runOnIdle { mode.value = LocalUsageMode.CHAT }
        compose.onNodeWithText(title).performClick()
        compose.runOnIdle { assertEquals(2, opens); available.value = false }
        compose.onNodeWithText(title).assertDoesNotExist()
    }

}
