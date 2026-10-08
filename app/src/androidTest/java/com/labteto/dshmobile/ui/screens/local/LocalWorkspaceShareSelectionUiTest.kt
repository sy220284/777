package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.session.LocalConversationFiles
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview
import com.labteto.dshmobile.ui.theme.DshTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

class LocalWorkspaceShareSelectionUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun artifactsSupportOneAndMultipleSelectionsAndCancel() {
        val files = listOf(
            LocalWorkspaceFile("results/first.txt", 5L, 1L),
            LocalWorkspaceFile("results/second.pdf", 10L, 2L),
        )
        compose.setContent {
            DshTheme {
                LocalWorkspaceFilesDialog(
                    mode = LocalFilesMode.CONVERSATION,
                    sessionId = "share-selection-test",
                    workspacePath = File(context.filesDir, "local-harness/workspace").absolutePath,
                    loadWorkspace = { emptyList() },
                    loadConversation = { LocalConversationFiles(artifacts = files) },
                    loadPreview = { path -> LocalWorkspaceFilePreview(files.first { it.path == path }) },
                    onDismiss = {},
                )
            }
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("first.txt").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.local_files_select)).performClick()
        compose.onNodeWithText("first.txt").performClick()
        compose.onNodeWithText(context.getString(R.string.local_files_selected_count, 1)).assertExists()
        compose.onNodeWithText("second.pdf").performClick()
        compose.onNodeWithText(context.getString(R.string.local_files_selected_count, 2)).assertExists()
        compose.onNodeWithText(context.getString(R.string.local_files_share_selected)).assertExists()
        compose.onNodeWithText(context.getString(R.string.common_cancel)).performClick()
        compose.onNodeWithText(context.getString(R.string.local_files_select)).assertExists()
    }
}
