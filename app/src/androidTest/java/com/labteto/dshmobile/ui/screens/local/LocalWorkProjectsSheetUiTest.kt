package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.labteto.dshmobile.local.work.LocalWorkProject
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
        var opened: String? = null
        val snapshots = MutableStateFlow(LocalWorkProjectSnapshot())
        val actions = LocalWorkProjectsUiActions(
            snapshots = snapshots,
            create = { name, root ->
                created = name to root
                snapshots.value = LocalWorkProjectSnapshot(
                    projects = listOf(LocalWorkProject(
                        id = "11111111-1111-1111-1111-111111111111",
                        name = name,
                        root = "项目/测试项目-11111111",
                        createdAtMs = 1,
                        updatedAtMs = 1,
                        requestId = "11111111-1111-1111-1111-111111111111",
                    )),
                    revision = 1,
                )
            },
            importExisting = { _, _, _ -> },
            rename = { _, _ -> false },
            update = { _, _, _ -> false },
            changeRoot = { _, _ -> false },
            moveDirectory = { _, _, _ -> false },
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
                    onOpenProject = { opened = it },
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
        compose.onNodeWithText("打开项目文件").performClick()
        compose.runOnIdle { assertEquals("项目/测试项目-11111111", opened) }
    }
}
