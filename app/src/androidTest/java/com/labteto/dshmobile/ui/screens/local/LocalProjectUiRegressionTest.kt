package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.project.LocalProjectCatalogState
import com.labteto.dshmobile.local.project.LocalProject
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.CompletableDeferred
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
        compose.onNodeWithTag("local_project_confirm_delete").performClick()
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

    @Test fun failedProjectDeletionPreservesSelectionAndAllowsExplicitRetry() {
        val catalog = MutableStateFlow(LocalProjectCatalogState(
            projects = listOf(
                LocalProject("local-workspace", "默认项目"),
                LocalProject("custom", "历史引用项目"),
            ),
            activeId = "custom",
        ))
        var attempts = 0
        compose.setContent {
            DshTheme {
                LocalProjectScreen(
                    actions = LocalProjectUiActions(
                        catalog = catalog,
                        recoveryNotice = MutableStateFlow(null),
                        backupAndReset = {},
                        createProjectWorkSession = { false },
                        onProjectSessionAccepted = {},
                        create = { "" }, select = {}, rename = { _, _ -> },
                        delete = { id ->
                            attempts++
                            if (attempts == 1) throw IllegalStateException("历史会话仍引用项目")
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
        compose.onNodeWithTag("local_project_confirm_delete").performClick()
        compose.waitUntil(5_000) { attempts == 1 }
        compose.onNodeWithText("历史会话仍引用项目").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("custom", catalog.value.activeId)
            assertEquals(2, catalog.value.projects.size)
        }
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithTag("local_project_confirm_delete").performClick()
        compose.waitUntil(5_000) { attempts == 2 && catalog.value.activeId == "local-workspace" }
        compose.runOnIdle { assertEquals(1, catalog.value.projects.size) }
    }

    @Test fun cancellingProjectDeletionDoesNotInvokeAnyWrite() {
        val catalog = MutableStateFlow(LocalProjectCatalogState(
            projects = listOf(
                LocalProject("local-workspace", "默认项目"),
                LocalProject("custom", "不可意外删除"),
            ),
            activeId = "custom",
        ))
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
                        create = { "" }, select = {}, rename = { _, _ -> },
                        delete = { called++ },
                        updateInstructions = { _, _ -> },
                    ),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.local_project_delete)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.common_cancel)).performClick()
        compose.runOnIdle {
            assertEquals(0, called)
            assertEquals("custom", catalog.value.activeId)
            assertEquals(2, catalog.value.projects.size)
        }
    }

    @Test fun doubleDeleteIsBlockedWhileTheFirstRequestIsAwaitingStorage() {
        val catalog = MutableStateFlow(LocalProjectCatalogState(
            projects = listOf(
                LocalProject("local-workspace", "默认项目"),
                LocalProject("custom", "等待删除"),
            ),
            activeId = "custom",
        ))
        val unblock = CompletableDeferred<Unit>()
        var calls = 0
        compose.setContent {
            DshTheme {
                LocalProjectScreen(
                    actions = LocalProjectUiActions(
                        catalog = catalog,
                        recoveryNotice = MutableStateFlow(null),
                        backupAndReset = {},
                        createProjectWorkSession = { false },
                        onProjectSessionAccepted = {},
                        create = { "" }, select = {}, rename = { _, _ -> },
                        delete = { id ->
                            calls++
                            unblock.await()
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
        compose.onNodeWithText(context.getString(R.string.local_project_delete)).performScrollTo().performClick()
        compose.onNodeWithTag("local_project_confirm_delete").performClick()
        compose.waitUntil(5_000) { calls == 1 }
        compose.onNodeWithTag("local_project_confirm_delete").assertIsNotEnabled()
        compose.runOnIdle { unblock.complete(Unit) }
        compose.waitUntil(5_000) { catalog.value.activeId == "local-workspace" }
        compose.runOnIdle { assertEquals(1, calls) }
    }
}
