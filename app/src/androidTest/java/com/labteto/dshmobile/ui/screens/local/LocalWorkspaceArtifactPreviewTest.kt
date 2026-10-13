package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
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

    @Test fun changingRequestOnMountedPageOpensTheNewArtifactWithoutDuplicateLoad() {
        val path = mutableStateOf<String?>("results/first.md")
        val opened = CopyOnWriteArrayList<String>()
        compose.setContent { DshTheme {
            LocalWorkspaceFilesDialog(
                mode = LocalFilesMode.CONVERSATION, sessionId = "session", workspacePath = "/workspace",
                loadWorkspace = { emptyList() }, loadConversation = { LocalConversationFiles() },
                loadPreview = { requested ->
                    opened += requested
                    LocalWorkspaceFilePreview(LocalWorkspaceFile(requested, 20, 1), "body:$requested")
                },
                initialFilePath = path.value,
                onInitialFilePathConsumed = { consumed -> if (path.value == consumed) path.value = null },
                onDismiss = {},
            )
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("body:results/first.md").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { path.value = "results/second.md" }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("body:results/second.md").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("body:results/first.md").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("results/first.md", "results/second.md"), opened.toList()) }
    }

    @Test fun sameArtifactCanBeRequestedAgainAfterReturningToTheList() {
        val path = mutableStateOf<String?>("results/report.md")
        val opened = CopyOnWriteArrayList<String>()
        compose.setContent { DshTheme {
            LocalWorkspaceFilesDialog(
                mode = LocalFilesMode.CONVERSATION, sessionId = "session", workspacePath = "/workspace",
                loadWorkspace = { emptyList() }, loadConversation = { LocalConversationFiles() },
                loadPreview = { requested ->
                    opened += requested
                    LocalWorkspaceFilePreview(LocalWorkspaceFile(requested, 20, 1), "repeat body")
                },
                initialFilePath = path.value,
                onInitialFilePathConsumed = { consumed -> if (path.value == consumed) path.value = null },
                onDismiss = {},
            )
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("repeat body").fetchSemanticsNodes().isNotEmpty() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(R.string.local_files_back_to_files)).performClick()
        compose.onNodeWithText("repeat body").assertDoesNotExist()
        compose.runOnIdle { path.value = "results/report.md" }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("repeat body").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf("results/report.md", "results/report.md"), opened.toList()) }
    }

    @Test fun freshOrdinaryEntryClearsThePreviousArtifactPreview() {
        val path = mutableStateOf<String?>("results/report.md")
        val request = mutableStateOf(1)
        compose.setContent { DshTheme {
            LocalWorkspaceFilesDialog(
                mode = LocalFilesMode.CONVERSATION, sessionId = "session", workspacePath = "/workspace",
                loadWorkspace = { emptyList() }, loadConversation = { LocalConversationFiles() },
                loadPreview = { requested ->
                    LocalWorkspaceFilePreview(LocalWorkspaceFile(requested, 20, 1), "previous artifact")
                },
                initialFilePath = path.value, entryRequest = request.value,
                onInitialFilePathConsumed = { consumed -> if (path.value == consumed) path.value = null },
                onDismiss = {},
            )
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("previous artifact").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { request.value++ }
        compose.waitForIdle()
        compose.onNodeWithText("previous artifact").assertDoesNotExist()
    }
}
