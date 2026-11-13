package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.labteto.dshmobile.local.work.LocalWorkProjectSnapshot
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalWorkProjectsSheetUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newProjectNeedsOnlyNameAndUsesAutomaticWorkspaceDirectory() {
        var created: Pair<String, String>? = null
        val actions = LocalWorkProjectsUiActions(
            snapshots = MutableStateFlow(LocalWorkProjectSnapshot()),
            create = { name, root -> created = name to root },
            importExisting = { _, _, _ -> },
            rename = { _, _ -> false },
            update = { _, _, _ -> false },
            changeRoot = { _, _ -> false },
            move = { _, _ -> false },
            delete = { false },
            assignCurrentSession = { _, _ -> false },
        )
        compose.setContent {
            DshTheme {
                LocalWorkProjectsSheet(
                    actions = actions,
                    sessionId = "project-create",
                    currentFolder = ".",
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("新建项目").performClick()
        compose.onNodeWithText("将自动创建独立的本地项目文件夹，直接保存即可。").assertExists()
        assertEquals(1, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("测试项目")
        compose.onNodeWithText("保存项目").performClick()
        compose.runOnIdle { assertEquals("测试项目" to "", created) }
    }
}
