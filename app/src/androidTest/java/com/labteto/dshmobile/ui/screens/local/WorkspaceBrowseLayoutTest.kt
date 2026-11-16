package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.session.LocalConversationFiles
import com.labteto.dshmobile.local.work.LocalWorkProjectSnapshot
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.After
import com.labteto.dshmobile.ui.audit.captureUiAudit
import org.junit.Rule
import org.junit.Test

/** Browsing must keep real folders in the first half of a compact phone viewport. */
class WorkspaceBrowseLayoutTest {
    @get:Rule val compose = createComposeRule()

    @After fun keepRenderedLayoutEvidence() = captureUiAudit(compose, "workspace-320dp")

    @Test fun secondaryOperationsDoNotPushFoldersBelowTheFirstHalfOfThePhone() {
        val actions = LocalWorkProjectsUiActions(
            snapshots = MutableStateFlow(LocalWorkProjectSnapshot()),
            create = { _, _ -> }, importExisting = { _, _, _ -> },
            rename = { _, _ -> true }, update = { _, _, _ -> true },
            changeRoot = { _, _ -> true }, moveDirectory = { _, _, _ -> true },
            move = { _, _ -> true }, delete = { true }, assignCurrentSession = { _, _ -> true },
        )
        compose.setContent {
            DshTheme {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    LocalWorkspaceFilesDialog(
                        mode = LocalFilesMode.WORKSPACE,
                        sessionId = "layout-test", workspacePath = "/layout-test",
                        loadWorkspace = { emptyList() },
                        importFromPhone = { "unused" }, projectActions = actions,
                        compareLocalGit = { _, _ -> error("Not invoked while browsing") },
                        loadConversation = { LocalConversationFiles() },
                        loadPreview = { error("Not invoked while browsing") },
                        onDismiss = {},
                    )
                }
            }
        }
        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.local_workspace_tasks)
        compose.waitUntil(30_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(label)).fetchSemanticsNodes().isNotEmpty()
        }
        val folderTop = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
        val limit = with(compose.density) { 260.dp.toPx() }
        assertTrue("Folders must lead the page, rather than a stack of secondary actions: y=$folderTop", folderTop < limit)
    }
}
