package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.shenyan.SyFullScreenDialog
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Video-audit regressions: task actions must be discoverable and fullscreen content isolated. */
class UiPolishInteractionRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyGroupChatOffersDirectPersonaCreation() {
        var createRequests = 0
        compose.setContent {
            DshTheme {
                GroupChatMemberPickerSheet(
                    entries = emptyList(),
                    currentIds = emptyList(),
                    enabled = true,
                    onSave = { false },
                    onCreatePersona = { createRequests++ },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("group-chat-create-persona")
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, createRequests) }
    }

    @Test fun oneAvailablePersonaStillOffersCreationOfSecondMember() {
        var createRequests = 0
        compose.setContent {
            DshTheme {
                GroupChatMemberPickerSheet(
                    entries = listOf(PersonaGalleryEntry(
                        "first", PersonaProfile(id = "first", name = "第一位人物"))),
                    currentIds = emptyList(),
                    enabled = true,
                    onSave = { false },
                    onCreatePersona = { createRequests++ },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("group-chat-create-persona")
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, createRequests) }
    }

    @Test fun fileSelectionAndMoreOperationsRemainReachable() {
        var selectRequests = 0
        var projectsRequests = 0
        compose.setContent {
            DshTheme {
                LocalWorkspaceBrowseToolbar(
                    importing = false,
                    enabled = true,
                    onImport = null,
                    onProjects = { projectsRequests++ },
                    onReview = null,
                    onDownloads = null,
                    onSelect = { selectRequests++ },
                )
            }
        }
        compose.onNodeWithTag("work_files_select").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("更多文件操作").assertIsDisplayed()
        compose.onNodeWithTag("work_files_more").assertIsDisplayed().performClick()
        compose.onNodeWithTag("work_projects_open").assertExists().performClick()
        compose.runOnIdle {
            assertEquals(1, selectRequests)
            assertEquals(1, projectsRequests)
        }
    }

    @Test fun deepWorkspaceDirectoryProvidesExactAncestorNavigation() {
        val breadcrumbs = workspaceBreadcrumbs(
            "任务/2026/十一月/具有很长名称的目录", "任务", "任务文件",
        )
        assertEquals(
            listOf("任务", "任务/2026", "任务/2026/十一月", "任务/2026/十一月/具有很长名称的目录"),
            breadcrumbs.map { it.directory },
        )
        var destination = ""
        compose.setContent {
            DshTheme {
                LocalWorkspacePathBreadcrumb(
                    directory = "任务/2026/十一月/具有很长名称的目录",
                    section = 0,
                    onNavigate = { destination = it },
                )
            }
        }
        compose.onNodeWithTag("workspace_path_breadcrumb").assertExists()
        compose.onNodeWithTag("workspace_breadcrumb_1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("任务/2026", destination) }
    }

    @Test fun fullscreenDialogHasOwnSceneBackground() {
        compose.setContent {
            DshTheme {
                SyFullScreenDialog(onDismiss = {}) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.onNodeWithTag("syFullScreenDialogBackdrop").assertExists()
    }
}
