package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.project.LocalProjectCatalogState
import com.labteto.dshmobile.local.project.LocalProject
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalProjectUiRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun selectedProjectStartsExactlyOneWorkSession() {
        var started = 0
        var navigated = 0
        compose.setContent {
            DshTheme {
                LocalProjectScreen(
                    actions = LocalProjectUiActions(
                        catalog = MutableStateFlow(LocalProjectCatalogState()),
                        recoveryNotice = MutableStateFlow<String?>(null),
                        backupAndReset = {},
                        createProjectWorkSession = { started++; true },
                        onProjectSessionAccepted = { navigated++ },
                        create = { "" }, select = {}, rename = { _, _ -> },
                        updateInstructions = { _, _ -> },
                    ),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.local_project_start_work_session)).performClick()
        compose.runOnIdle {
            assertEquals(1, started)
            assertEquals(1, navigated)
        }
    }

    @Test fun projectDeletionRequiresConfirmationAndUpdatesSelection() {
        val catalog = MutableStateFlow(
            LocalProjectCatalogState(
                projects = listOf(
                    LocalProject("local-workspace", "默认项目"),
                    LocalProject("custom", "待删除项目"),
                ),
                activeId = "custom",
            ),
        )
        var called = 0
        compose.setContent {
            DshTheme {
                LocalProjectScreen(
                    actions = LocalProjectUiActions(
                        catalog = catalog,
                        recoveryNotice = MutableStateFlow(null),
                        backupAndReset = {},
                        createProjectWorkSession = { false },
                        onProjectSessionAccepted = {},
                        create = { "" },
                        select = {},
                        rename = { _, _ -> },
                        delete = { id ->
                            called++
                            catalog.value = catalog.value.copy(
                                projects = catalog.value.projects.filterNot { it.id == id },
                                activeId = "local-workspace",
                            )
                        },
                        updateInstructions = { _, _ -> },
                    ),
                    onBack = {},
                )
            }
        }
        val label = context.getString(R.string.local_project_delete)
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onAllNodesWithText(label).onLast().performClick()
        compose.waitUntil(5_000) { called == 1 }
        compose.runOnIdle {
            assertEquals("local-workspace", catalog.value.activeId)
            assertEquals(1, catalog.value.projects.size)
        }
    }

    @Test fun corruptedCatalogBlocksMutatingSessionActionsUntilExplicitBackup() {
        var reset = 0
        compose.setContent {
            DshTheme {
                LocalProjectScreen(
                    actions = LocalProjectUiActions(
                        catalog = MutableStateFlow(LocalProjectCatalogState()),
                        recoveryNotice = MutableStateFlow<String?>("目录损坏"),
                        backupAndReset = { reset++ },
                        createProjectWorkSession = { error("invalid") },
                        onProjectSessionAccepted = {},
                        create = { error("invalid") }, select = {}, rename = { _, _ -> },
                        updateInstructions = { _, _ -> },
                    ),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.local_project_start_work_session)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_project_backup_and_reset)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, reset) }
    }
}
