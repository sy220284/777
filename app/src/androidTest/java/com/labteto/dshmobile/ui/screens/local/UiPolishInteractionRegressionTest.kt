package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
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
        compose.onNodeWithTag("work_files_more").assertIsDisplayed().performClick()
        compose.onNodeWithTag("work_projects_open").assertExists().performClick()
        compose.runOnIdle {
            assertEquals(1, selectRequests)
            assertEquals(1, projectsRequests)
        }
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
