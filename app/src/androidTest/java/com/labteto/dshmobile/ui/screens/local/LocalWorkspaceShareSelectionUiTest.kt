package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
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
    fun gitReviewScopeTabsReuseOneEntryWithoutExposingExtraForms() {
        val calls = mutableListOf<String>()
        compose.setContent {
            DshTheme {
                LocalWorkspaceFilesDialog(
                    mode = LocalFilesMode.WORKSPACE,
                    sessionId = "git-source-tabs",
                    workspacePath = File(context.filesDir, "local-harness/workspace").absolutePath,
                    loadWorkspace = { emptyList() },
                    compareLocalGit = { _, _ ->
                        calls += "local"
                        com.labteto.dshmobile.local.presentation.LocalRemoteGitDiffUi(
                            "worktree/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            "local diff", "@@ -1 +1 @@\\n+local", truncated = false,
                        )
                    },
                    remoteGitBranches = { listOf("origin/main") },
                    compareRemoteGit = { _, _, _ ->
                        calls += "remote"
                        com.labteto.dshmobile.local.presentation.LocalRemoteGitDiffUi(
                            "refs/remotes/origin/main", "remote diff",
                            "@@ -1 +1 @@\\n+remote", truncated = false,
                        )
                    },
                    compareCommitGit = { _, _ ->
                        calls += "commit"
                        com.labteto.dshmobile.local.presentation.LocalRemoteGitDiffUi(
                            "commit/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "commit diff",
                            "@@ -1 +1 @@\\n+commit", truncated = false,
                        )
                    },
                    loadConversation = { error("不应查询会话") },
                    loadPreview = { error("不应预览") },
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("代码审查").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("代码审查").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("local diff").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("指定提交").performClick()
        compose.onNodeWithText("读取提交差异").assertExists()
        compose.onNodeWithText("远端分支").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("origin/main").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("origin/main").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("remote diff").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertEquals(listOf("local", "remote"), calls) }
    }

    @Test
    fun workspaceGitCompareUsesSelectedTrackingRefAndShowsActualPatch() {
        val calls = mutableListOf<Triple<String, String, String>>()
        compose.setContent {
            DshTheme {
                LocalWorkspaceFilesDialog(
                    mode = LocalFilesMode.WORKSPACE,
                    sessionId = "git-compare",
                    workspacePath = File(context.filesDir, "local-harness/workspace").absolutePath,
                    loadWorkspace = { emptyList() },
                    remoteGitBranches = { directory ->
                        assertEquals(".", directory)
                        listOf("origin/main", "upstream/release/v2")
                    },
                    compareRemoteGit = { directory, remote, branch ->
                        calls += Triple(directory, remote, branch)
                        com.labteto.dshmobile.local.presentation.LocalRemoteGitDiffUi(
                            "refs/remotes/$remote/$branch",
                            "1 file changed, 1 insertion(+)",
                            "@@ -1 +1 @@\\n+changed",
                            truncated = false,
                        )
                    },
                    loadConversation = { error("不能扫描会话") },
                    loadPreview = { error("不需要文件预览") },
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("代码审查").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("代码审查").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("upstream/release/v2").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("upstream/release/v2").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("差异预览").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("1 file changed, 1 insertion(+)").assertExists()
        compose.runOnIdle { assertEquals(listOf(Triple(".", "upstream", "release/v2")), calls) }
    }

    @Test
    fun workspaceLocalGitDiffRestoresSavedReviewWithoutRemote() {
        val scanned = mutableListOf<String>()
        val recovered = mutableListOf<String>()
        compose.setContent {
            DshTheme {
                LocalWorkspaceFilesDialog(
                    mode = LocalFilesMode.WORKSPACE,
                    sessionId = "local-git-review",
                    workspacePath = File(context.filesDir, "local-harness/workspace").absolutePath,
                    loadWorkspace = { emptyList() },
                    compareLocalGit = { directory, includeUntracked ->
                        assertEquals(false, includeUntracked)
                        scanned += directory
                        com.labteto.dshmobile.local.presentation.LocalRemoteGitDiffUi(
                            "worktree/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            "1 file changed, 1 insertion(+)",
                            "@@ -1 +1 @@\\n+fixed",
                            truncated = false,
                        )
                    },
                    findIndependentCodeReview = { session, repository, diff ->
                        recovered += "$session:$repository:${diff.trackingRef}"
                        com.labteto.dshmobile.local.work.LocalIndependentCodeReview(
                            "review-1", diff.trackingRef, "digest",
                            "已保存的历史审查报告", 7L, humanAccepted = true,
                        )
                    },
                    loadConversation = { error("不应查询会话") },
                    loadPreview = { error("不应预览文件") },
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("代码审查").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("代码审查").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("已保存的历史审查报告")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("人工接纳已记录").assertExists()
        compose.runOnIdle {
            assertEquals(listOf("."), scanned)
            assertEquals(listOf("local-git-review:.:worktree/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), recovered)
        }
    }

    @Test
    fun commitReviewHistoryFailuresStayIndependentAcrossReviewTypes() {
        compose.setContent {
            DshTheme {
                LocalWorkspaceFilesDialog(
                    mode = LocalFilesMode.WORKSPACE,
                    sessionId = "commit-review-independent",
                    workspacePath = File(context.filesDir, "local-harness/workspace").absolutePath,
                    loadWorkspace = { emptyList() },
                    compareLocalGit = { _, _ ->
                        com.labteto.dshmobile.local.presentation.LocalRemoteGitDiffUi(
                            "worktree/source", "local changes", "@@ -1 +1 @@\\n+local", truncated = false,
                        )
                    },
                    compareCommitGit = { _, commit ->
                        com.labteto.dshmobile.local.presentation.LocalRemoteGitDiffUi(
                            "commit/$commit", "commit changes", "@@ -1 +1 @@\\n+commit", truncated = false,
                        )
                    },
                    findIndependentCodeReview = { _, _, diff ->
                        com.labteto.dshmobile.local.work.LocalIndependentCodeReview(
                            "saved-code", diff.trackingRef, "digest",
                            "独立代码历史仍可查看", 12L,
                        )
                    },
                    findIndependentSecurityReview = { _, _, _ ->
                        error("安全历史记录读取失败")
                    },
                    loadConversation = { error("不读取会话") },
                    loadPreview = { error("不读取预览") },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("代码审查").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("独立代码历史仍可查看").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("指定提交").performClick()
        compose.onNodeWithTag("work_git_commit_input").performTextInput("abcdef1234567")
        compose.onNodeWithTag("work_git_commit_load").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("独立代码历史仍可查看").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("安全历史记录读取失败").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("独立代码历史仍可查看").assertExists()
        compose.onNodeWithText("安全历史记录读取失败").assertExists()
    }

    @Test
    fun securityReviewApprovalRequiresExplicitConfirmation() {
        var approvals = 0
        compose.setContent {
            DshTheme {
                LocalWorkspaceFilesDialog(
                    mode = LocalFilesMode.WORKSPACE,
                    sessionId = "security-review",
                    workspacePath = File(context.filesDir, "local-harness/workspace").absolutePath,
                    loadWorkspace = { emptyList() },
                    compareLocalGit = { _, _ ->
                        com.labteto.dshmobile.local.presentation.LocalRemoteGitDiffUi(
                            "worktree/head-a", "1 file changed", "@@ -1 +1 @@\\n+secure = true",
                            truncated = false,
                        )
                    },
                    findIndependentSecurityReview = { _, _, diff ->
                        com.labteto.dshmobile.local.work.LocalSecurityReview(
                            "security-1", diff.trackingRef, "digest", "审查安全结论",
                            "allow", 9L,
                        )
                    },
                    approveIndependentSecurityReview = { _, _, _, _ ->
                        approvals++
                        true
                    },
                    loadConversation = { error("不应查询会话") },
                    loadPreview = { error("不应预览") },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("代码审查").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("审查安全结论").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("人工批准当前变更").performClick()
        compose.runOnIdle { assertEquals(0, approvals) }
        compose.onNodeWithText("确认安全放行").assertExists()
        compose.onNodeWithText("批准当前变更").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("当前版本已人工批准；修改代码后需重新审核。")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertEquals(1, approvals) }
    }

    @Test
    fun blockingSecurityVerdictCannotBeApprovedFromUi() {
        compose.setContent {
            DshTheme {
                LocalWorkspaceFilesDialog(
                    mode = LocalFilesMode.WORKSPACE,
                    sessionId = "security-review-block",
                    workspacePath = File(context.filesDir, "local-harness/workspace").absolutePath,
                    loadWorkspace = { emptyList() },
                    compareLocalGit = { _, _ ->
                        com.labteto.dshmobile.local.presentation.LocalRemoteGitDiffUi(
                            "worktree/head-b", "1 file changed", "@@ -1 +1 @@\\n+unsafe = true",
                            truncated = false,
                        )
                    },
                    findIndependentSecurityReview = { _, _, diff ->
                        com.labteto.dshmobile.local.work.LocalSecurityReview(
                            "security-blocked", diff.trackingRef, "digest",
                            "发现高危操作", "block", 10L,
                        )
                    },
                    approveIndependentSecurityReview = { _, _, _, _ ->
                        error("风险裁决禁止放行")
                    },
                    loadConversation = { error("不应查询会话") },
                    loadPreview = { error("不应预览") },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("代码审查").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("发现高危操作").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("人工批准当前变更").assertDoesNotExist()
    }

    @Test
    fun workspaceGitWithoutTrackingRefsExplainsWhatToDoInsteadOfPretendingSuccess() {
        compose.setContent {
            DshTheme {
                LocalWorkspaceFilesDialog(
                    mode = LocalFilesMode.WORKSPACE,
                    sessionId = "git-no-branches",
                    workspacePath = File(context.filesDir, "local-harness/workspace").absolutePath,
                    loadWorkspace = { emptyList() },
                    remoteGitBranches = { emptyList() },
                    compareRemoteGit = { _, _, _ -> error("不能比较不存在的分支") },
                    loadConversation = { error("不能扫描会话") },
                    loadPreview = { error("不需要文件预览") },
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("代码审查").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("代码审查").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("当前目录没有可比较的本地远端跟踪分支。请先在该 Git 仓库中获取远端分支。")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("差异预览").assertDoesNotExist()
    }

    @Test
    fun returningWhilePreviewLoadsDoesNotReopenTheOldFile() {
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val file = LocalWorkspaceFile("任务/慢文件.md", 10L, 1L)
        try {
            compose.setContent {
                DshTheme {
                    LocalWorkspaceFilesDialog(
                        mode = LocalFilesMode.WORKSPACE, sessionId = "preview-cancellation",
                        workspacePath = File(context.filesDir, "local-harness/workspace").absolutePath,
                        loadWorkspace = { emptyList() },
                        loadDirectoryPage = { _, _ -> LocalWorkspaceDirectoryPage(listOf(
                            LocalWorkspaceDirectoryEntry(file.path, false, file.bytes, file.modifiedAt)), null) },
                        loadConversation = { error("不应加载会话") },
                        loadPreview = {
                            started.set(true)
                            try {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                                LocalWorkspaceFilePreview(file, "迟到的旧预览正文")
                            } finally { finished.set(true) }
                        }, onDismiss = {})
                }
            }
            compose.onNodeWithText(context.getString(R.string.local_workspace_tasks)).performClick()
            compose.onNodeWithText("慢文件.md").performClick()
            compose.waitUntil(5_000) { started.get() }
            compose.onNodeWithContentDescription(context.getString(R.string.local_files_back_to_files)).performClick()
            release.complete(Unit)
            compose.waitUntil(5_000) { finished.get() }
            compose.waitForIdle()
            compose.onNodeWithText("迟到的旧预览正文").assertDoesNotExist()
            compose.onNodeWithText("慢文件.md").assertExists()
        } finally {
            // Never leave a non-cancellable fixture waiting if an assertion or navigation fails.
            release.complete(Unit)
        }
    }

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

    @Test
    fun selectedArtifactsSaveToChineseDownloadsAndReportActualResult() {
        val files = listOf(
            LocalWorkspaceFile("成果/第一份.md", 5L, 1L),
            LocalWorkspaceFile("成果/第二份.pdf", 10L, 2L),
        )
        val saved = java.util.Collections.synchronizedList(mutableListOf<String>())
        compose.setContent {
            DshTheme {
                LocalWorkspaceFilesDialog(
                    mode = LocalFilesMode.CONVERSATION,
                    sessionId = "downloads-selection-test",
                    workspacePath = File(context.filesDir, "local-harness/workspace").absolutePath,
                    loadWorkspace = { emptyList() },
                    saveToDownloads = { path ->
                        saved += path
                        "Download/神言神语/${path.substringAfterLast('/')}"
                    },
                    loadConversation = { LocalConversationFiles(artifacts = files) },
                    loadPreview = { path -> LocalWorkspaceFilePreview(files.first { it.path == path }) },
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("第一份.md").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.local_files_select)).performClick()
        compose.onNodeWithText("第一份.md").performClick()
        compose.onNodeWithText("第二份.pdf").performClick()
        compose.onNodeWithText("保存到下载").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("已保存 2 个文件到 Download/神言神语")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals(listOf("成果/第一份.md", "成果/第二份.pdf"), saved)
        }
        compose.onNodeWithText("查看下载").assertExists()
    }
}
