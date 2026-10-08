package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.project.LocalProject
import com.labteto.dshmobile.local.project.LocalProjectCatalogState
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalProjectRenameRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun renameExistingProjectKeepsItsIdentityAndInstructions() {
        val catalog = MutableStateFlow(
            LocalProjectCatalogState(listOf(LocalProject("project-1", "旧名称", "原项目规则")), "project-1"),
        )
        val actions = LocalProjectUiActions(
            catalog = catalog,
            recoveryNotice = MutableStateFlow(null),
            backupAndReset = {},
            createProjectWorkSession = { false },
            onProjectSessionAccepted = {},
            create = { error("No create expected") },
            select = {},
            rename = { id, next ->
                catalog.value = catalog.value.copy(
                    projects = catalog.value.projects.map { if (it.id == id) it.copy(name = next.trim()) else it },
                )
            },
            updateInstructions = { _, _ -> error("No instruction mutation expected") },
        )
        compose.setContent {
            DshTheme { LocalProjectScreen(actions, onBack = {}) }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.local_project_rename_hint))
            .performScrollTo()
            .performTextReplacement("新名称")
        compose.onNodeWithText(context.getString(R.string.local_project_rename))
            .performScrollTo()
            .performClick()
        compose.runOnIdle {
            assertEquals("project-1", catalog.value.projects.single().id)
            assertEquals("新名称", catalog.value.projects.single().name)
            assertEquals("原项目规则", catalog.value.projects.single().instructions)
        }
        compose.onNodeWithText("✓ 新名称").assertIsDisplayed()
    }
}
