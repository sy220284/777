package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.labteto.dshmobile.local.session.LocalConversationFiles
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview
import com.labteto.dshmobile.ui.theme.DshTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalWorkspaceArtifactPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun requestedArtifactOpensEvenOutsideConversationFileList() {
        val opened = CopyOnWriteArrayList<String>()
        compose.setContent {
            DshTheme {
                LocalWorkspaceFilesDialog(
                    mode = LocalFilesMode.CONVERSATION, sessionId = "session", workspacePath = "/workspace",
                    loadWorkspace = { emptyList() }, loadConversation = { LocalConversationFiles() },
                    loadPreview = { path ->
                        opened += path
                        LocalWorkspaceFilePreview(LocalWorkspaceFile(path, 20, 1), "exact artifact body")
                    },
                    initialFilePath = "results/report.md", onDismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("exact artifact body").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("exact artifact body").assertExists()
        compose.runOnIdle { assertEquals(listOf("results/report.md"), opened.toList()) }
    }
}
