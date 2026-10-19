package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalSessionSummary
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
                    onOpenGroupChat = {},
                    onOpenPersonaGallery = {},
                    onOpenDiary = {},
                    onTasks = {},
                    onWorkspaceFiles = {},
                    onTools = { openToolsCount++ },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.app_extended_capabilities)).assertDoesNotExist()
        compose.onNodeWithText("工作项目").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_run_center)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.chatlist_workspace_files)).assertExists()
        compose.onNodeWithText(context.getString(R.string.local_drawer_projects_plain_hint)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_drawer_workspace_plain_hint)).assertDoesNotExist()
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
                    onOpenGroupChat = {},
                    onOpenPersonaGallery = {},
                    onOpenDiary = {},
                    onTasks = {},
                    onWorkspaceFiles = {},
                    onTools = {},
                )
            }
        }

        compose.onNodeWithText("工作项目").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.persona_gallery_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.local_group_chat_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.tasks_chat_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.chat_diary_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.local_run_center)).assertDoesNotExist()
    }
    @Test
    fun workSidebarLabelsCallMatchingFeatureActions() {
        val opened = mutableListOf<String>()
        compose.setContent {
            DshTheme {
                DrawerQuickActions(
                    usageMode = LocalUsageMode.WORK,
                    onOpenGroupChat = {}, onOpenPersonaGallery = {}, onOpenDiary = {},
                    onTasks = { opened += "tasks" },
                    onWorkspaceFiles = { opened += "workspace" },
                    onTools = { opened += "tools" },
                    onUsage = { opened += "usage" },
                )
            }
        }
        listOf(
            R.string.chatlist_workspace_files to "workspace",
            R.string.tasks_title to "tasks",
            R.string.tools_title to "tools",
            R.string.usage_calculation_title to "usage",
        ).forEach { (label, action) ->
            compose.onNodeWithText(context.getString(label)).performClick()
            compose.runOnIdle { assertEquals(action, opened.last()) }
        }
        compose.runOnIdle { assertEquals(4, opened.size) }
        compose.onNodeWithText("沉浸阅读").assertDoesNotExist()
    }

    @Test
    fun chatSidebarLabelsCallMatchingFeatureActions() {
        val opened = mutableListOf<String>()
        compose.setContent {
            DshTheme {
                DrawerQuickActions(
                    usageMode = LocalUsageMode.CHAT,
                    onOpenGroupChat = { opened += "group" },
                    onOpenPersonaGallery = { opened += "gallery" },
                    onOpenDiary = { opened += "diary" },
                    onTasks = { opened += "chat_tasks" },
                    onWorkspaceFiles = {}, onTools = {},
                )
            }
        }
        listOf(
            R.string.persona_gallery_title to "gallery",
            R.string.local_group_chat_title to "group",
            R.string.tasks_chat_title to "chat_tasks",
            R.string.chat_diary_title to "diary",
        ).forEach { (label, action) ->
            compose.onNodeWithText(context.getString(label)).performClick()
            compose.runOnIdle { assertEquals(action, opened.last()) }
        }
        compose.runOnIdle { assertEquals(4, opened.size) }
        compose.onNodeWithText("沉浸阅读").assertDoesNotExist()
    }

    @Test
    fun quotaEntryAppearsInBothModesAndDisappearsWithoutMiniMaxAction() {
        var opens = 0
        val mode = androidx.compose.runtime.mutableStateOf(LocalUsageMode.WORK)
        val available = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            DshTheme {
                DrawerQuickActions(
                    usageMode = mode.value,
                    onOpenGroupChat = {}, onOpenPersonaGallery = {}, onOpenDiary = {},
                    onTasks = {}, onWorkspaceFiles = {}, onTools = {}, onMiniMaxQuota = if (available.value) { { opens++ } } else null,
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


    @Test
    fun sessionCannotBeDeletedBySwipeButLongPressOffersDelete() {
        val title = "滑动删除可见性测试会话标题足够长"
        val session = LocalSessionSummary(
            id = "swipe-regression",
            title = title,
            updatedAt = 10L,
            usageMode = LocalUsageMode.WORK,
        )
        var deleteRequests = 0
        compose.setContent {
            DshTheme {
                LocalSessionDrawerRow(
                    session = session,
                    displayTitle = title,
                    galleryEntry = null,
                    current = true,
                    pinned = false,
                    running = false,
                    selected = false,
                    selectionOpen = false,
                    onClick = {},
                    onSelectMultiple = {},
                    onRename = {},
                    onTogglePinned = {},
                    onDelete = { deleteRequests++ },
                )
            }
        }

        val deleteLabel = context.getString(R.string.local_delete_session)
        // No swipe-only delete control may be exposed, even after either horizontal swipe.
        compose.onNodeWithContentDescription(deleteLabel).assertDoesNotExist()
        compose.onNodeWithText(title).performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription(deleteLabel).assertDoesNotExist()
        compose.onNodeWithText(title).performTouchInput { swipeRight() }
        compose.onNodeWithContentDescription(deleteLabel).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, deleteRequests) }

        // All session management actions, including deletion, remain in the long-press menu.
        compose.onNodeWithText(title).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.common_rename)).assertExists()
        compose.onNodeWithText(deleteLabel).performClick()
        compose.runOnIdle { assertEquals(1, deleteRequests) }
    }

}
