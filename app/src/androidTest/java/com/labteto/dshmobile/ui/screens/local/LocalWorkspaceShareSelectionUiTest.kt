package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.files.LocalWorkspaceDirectoryEntry
import com.labteto.dshmobile.local.files.LocalWorkspaceDirectoryPage
import com.labteto.dshmobile.local.session.LocalConversationFiles
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview
import com.labteto.dshmobile.ui.theme.DshTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalWorkspaceShareSelectionUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun workspaceRootAndTaskFoldersDoNotTriggerGlobalFileScan() {
        var fullScans = 0
        var directoryReads = 0
        compose.setContent {
            DshTheme {
                LocalWorkspaceFilesDialog(
                    mode = LocalFilesMode.WORKSPACE,
                    sessionId = "root-fast-browser",
                    workspacePath = File(context.filesDir, "local-harness/workspace").absolutePath,
                    loadWorkspace = { fullScans++; emptyList() },
                    loadDirectory = { path ->
                        directoryReads++
                        if (path == "任务")
                            listOf(LocalWorkspaceDirectoryEntry("任务/第一项", true, 0L, 0L))
                        else emptyList()
                    },
                    loadConversation = { error("不能为工作区浏览扫描会话事件") },
                    loadPreview = { error("本测试不预览") },
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.local_workspace_tasks))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, fullScans)
        assertEquals(0, directoryReads)
        compose.onNodeWithText(context.getString(R.string.local_workspace_tasks)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("第一项").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, fullScans)
        assertEquals(1, directoryReads)
    }

    @Test
    fun scrollingPrefetchesNextDirectoryPageWithoutLoadMoreControls() {
        val pageRequests = mutableListOf<Int>()
        compose.setContent {
            DshTheme {
                LocalWorkspaceFilesDialog(
                    mode = LocalFilesMode.WORKSPACE,
                    sessionId = "seamless-paging",
                    workspacePath = File(context.filesDir, "local-harness/workspace").absolutePath,
                    loadWorkspace = { error("不允许在目录列表扫描整个工作区") },
                    loadDirectoryPage = { path, offset ->
                        if (path != "任务") LocalWorkspaceDirectoryPage(emptyList(), null)
                        else {
                            pageRequests += offset
                            LocalWorkspaceDirectoryPage(
                                entries = (offset until minOf(180, offset + 96)).map { n ->
                                    LocalWorkspaceDirectoryEntry("任务/文档-${n.toString().padStart(3, '0')}.md", false, 3L, 1L)
                                },
                                nextOffset = (offset + 96).takeIf { it < 180 },
                            )
                        }
                    },
                    loadConversation = { error("目录列表不应读取会话内容") },
                    loadPreview = { error("不需要预览") },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.local_workspace_tasks)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("文档-000.md").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollAction()).performScrollToIndex(68)
        compose.waitUntil(5_000) { pageRequests.contains(96) }
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToIndex(124)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("文档-124.md").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf(0, 96), pageRequests)
        compose.onNodeWithText("加载更多").assertDoesNotExist()
        compose.onNodeWithText("点击加载更多").assertDoesNotExist()
    }

    @Test
    fun searchRowsContinuePrefetchingOnScrollWithoutPagingButtons() {
        val offsets = mutableListOf<Int>()
        compose.setContent {
            DshTheme {
                LocalWorkspaceFilesDialog(
                    mode = LocalFilesMode.WORKSPACE,
                    sessionId = "search-seamless",
                    workspacePath = File(context.filesDir, "local-harness/workspace").absolutePath,
                    loadWorkspace = { error("搜索不应加载全量工作区列表") },
                    loadSearchPage = { query, offset ->
                        assertEquals("报告", query)
                        offsets += offset
                        com.labteto.dshmobile.local.files.LocalWorkspaceSearchPage(
                            files = (offset * 96 until minOf(180, offset * 96 + 96)).map { n ->
                                LocalWorkspaceFile("任务/报告-${n.toString().padStart(3, '0')}.md", 3L, 1L)
                            },
                            nextOffset = (offset + 1).takeIf { it * 96 < 180 },
                        )
                    },
                    loadConversation = { error("搜索不应加载会话数据") },
                    loadPreview = { error("不需要预览") },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.local_workspace_tasks)).assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("报告")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("报告-000.md").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollAction()).performScrollToIndex(68)
        compose.waitUntil(5_000) { offsets.contains(1) }
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToIndex(124)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("报告-124.md").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf(0, 1), offsets)
        compose.onNodeWithText("加载更多").assertDoesNotExist()
    }

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
