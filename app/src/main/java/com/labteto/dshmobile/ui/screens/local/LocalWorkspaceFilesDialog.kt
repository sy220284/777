package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.files.LocalWorkspaceDirectoryEntry
import com.labteto.dshmobile.local.files.LocalWorkspaceDirectoryPage
import com.labteto.dshmobile.local.files.LocalWorkspaceSearchPage
import com.labteto.dshmobile.local.files.LocalWorkspaceFileChange
import com.labteto.dshmobile.local.session.LocalConversationFiles
import com.labteto.dshmobile.local.presentation.LocalRemoteGitDiffUi
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.shenyan.designsystem.components.SyCheckboxControl
import com.shenyan.designsystem.components.SyEmptyState
import com.shenyan.designsystem.components.SyLoadingState
import com.labteto.dshmobile.ui.shenyan.SySearchField
import com.labteto.dshmobile.ui.shenyan.SyTopBar
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay

internal enum class LocalFilesMode { WORKSPACE, CONVERSATION }

private class FileSelectionUi(
    val active: Boolean,
    val selected: Set<String>,
    val toggle: (String) -> Unit,
    val begin: (String) -> Unit,
)

private val LocalFileSelectionUi = compositionLocalOf<FileSelectionUi?> { null }

@Composable
internal fun LocalWorkspaceFilesDialog(
    mode: LocalFilesMode,
    sessionId: String,
    workspacePath: String,
    loadWorkspace: suspend () -> List<LocalWorkspaceFile>,
    saveToDownloads: suspend (String) -> String = { error("下载目录保存功能不可用") },
    loadDirectory: suspend (String) -> List<LocalWorkspaceDirectoryEntry> = { emptyList() },
    loadDirectoryPage: suspend (String, Int) -> LocalWorkspaceDirectoryPage = { path, offset ->
        if (offset == 0) LocalWorkspaceDirectoryPage(loadDirectory(path), null)
        else LocalWorkspaceDirectoryPage(emptyList(), null)
    },
    loadSearch: suspend (String) -> List<LocalWorkspaceFile> = { query ->
        loadWorkspace().filter { it.path.contains(query, ignoreCase = true) }
    },
    loadSearchPage: suspend (String, Int) -> LocalWorkspaceSearchPage = { search, offset ->
        if (offset == 0) LocalWorkspaceSearchPage(loadSearch(search), null)
        else LocalWorkspaceSearchPage(emptyList(), null)
    },
    stopSearch: suspend (String) -> Boolean = { false },
    projectActions: LocalWorkProjectsUiActions? = null,
    observeChanges: (List<String>) -> kotlinx.coroutines.flow.Flow<LocalWorkspaceFileChange> =
        { kotlinx.coroutines.flow.emptyFlow() },
    remoteGitBranches: (suspend (String) -> List<String>)? = null,
    compareLocalGit: (suspend (String, Boolean) -> LocalRemoteGitDiffUi)? = null,
    compareRemoteGit: (suspend (String, String, String) -> LocalRemoteGitDiffUi)? = null,
    compareCommitGit: (suspend (String, String) -> LocalRemoteGitDiffUi)? = null,
    findIndependentCodeReview: (suspend (String, String, LocalRemoteGitDiffUi) -> com.labteto.dshmobile.local.work.LocalIndependentCodeReview?)? = null,
    runIndependentCodeReview: (suspend (String, String, LocalRemoteGitDiffUi) -> com.labteto.dshmobile.local.work.LocalIndependentCodeReview)? = null,
    acceptIndependentCodeReview: (suspend (String, String, com.labteto.dshmobile.local.work.LocalIndependentCodeReview, LocalRemoteGitDiffUi) -> Boolean)? = null,
    findIndependentSecurityReview: (suspend (String, String, LocalRemoteGitDiffUi) -> com.labteto.dshmobile.local.work.LocalSecurityReview?)? = null,
    runIndependentSecurityReview: (suspend (String, String, LocalRemoteGitDiffUi) -> com.labteto.dshmobile.local.work.LocalSecurityReview)? = null,
    approveIndependentSecurityReview: (suspend (String, String, com.labteto.dshmobile.local.work.LocalSecurityReview, LocalRemoteGitDiffUi) -> Boolean)? = null,
    loadConversation: suspend (String) -> LocalConversationFiles,
    loadPreview: suspend (String) -> LocalWorkspaceFilePreview,
    loadPreviewPage: suspend (String, Long) -> LocalWorkspaceFilePreview = { path, _ -> loadPreview(path) },
    onDismiss: () -> Unit,
    initialFilePath: String? = null,
    entryRequest: Int = 0,
    onInitialFilePathConsumed: (String) -> Unit = {},
) {
    var loading by remember(mode, sessionId) { mutableStateOf(true) }
    var error by remember(mode, sessionId) { mutableStateOf<String?>(null) }
    var workspace by remember(mode, sessionId) { mutableStateOf(emptyList<LocalWorkspaceFile>()) }
    var entries by remember(mode, sessionId) { mutableStateOf(emptyList<LocalWorkspaceDirectoryEntry>()) }
    var nextDirectoryOffset by remember(mode, sessionId) { mutableStateOf<Int?>(null) }
    var loadingNextPage by remember(mode, sessionId) { mutableStateOf(false) }
    var pageReadError by remember(mode, sessionId) { mutableStateOf<String?>(null) }
    var loadEpoch by remember(mode, sessionId) { mutableStateOf(0) }
    var lastLoadedSearch by remember(mode, sessionId) { mutableStateOf("") }
    var nextSearchOffset by remember(mode, sessionId) { mutableStateOf<Int?>(null) }
    var loadingNextSearchPage by remember(mode, sessionId) { mutableStateOf(false) }
    var searchPageError by remember(mode, sessionId) { mutableStateOf<String?>(null) }
    var conversation by remember(mode, sessionId) { mutableStateOf(LocalConversationFiles()) }
    var preview by remember(mode, sessionId) { mutableStateOf<LocalWorkspaceFilePreview?>(null) }
    val previewPreviousOffsets = remember(mode, sessionId) { mutableStateListOf<Long>() }
    var previewPath by com.labteto.dshmobile.ui.components.rememberNavigationEntryState<String?>(null, entryRequest, mode, sessionId)
    var loadedForEntry by remember(mode, sessionId, entryRequest) { mutableStateOf(false) }
    var previewLoading by remember(mode, sessionId) { mutableStateOf(false) }
    var previewEpoch by remember(mode, sessionId) { mutableStateOf(0L) }
    var previewJob by remember(mode, sessionId) { mutableStateOf<Job?>(null) }

    fun invalidatePreview() {
        previewEpoch++
        previewJob?.cancel()
        previewJob = null
        previewLoading = false
    }
    var directory by com.labteto.dshmobile.ui.components.rememberNavigationEntryState("", entryRequest, mode, sessionId)
    var section by com.labteto.dshmobile.ui.components.rememberNavigationEntryState(
        if (mode == LocalFilesMode.WORKSPACE) -1 else 1, entryRequest, mode, sessionId,
    )
    var query by com.labteto.dshmobile.ui.components.rememberNavigationEntryState("", entryRequest, mode, sessionId)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var selecting by rememberSaveable(mode, sessionId, section, directory) { mutableStateOf(false) }
    val selected = remember(mode, sessionId, section, directory) { mutableStateListOf<String>() }
    var sharing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var savedLocation by remember(mode, sessionId, section, directory) { mutableStateOf<String?>(null) }
    var shareError by remember(mode, sessionId, section, directory) { mutableStateOf<String?>(null) }
    var gitSheetOpen by remember(mode, sessionId) { mutableStateOf(false) }
    var gitIsLocal by remember(mode, sessionId) { mutableStateOf(false) }
    var gitIsCommit by remember(mode, sessionId) { mutableStateOf(false) }
    var gitCommitInput by remember(mode, sessionId) { mutableStateOf("") }
    var projectsSheetOpen by remember(mode, sessionId) { mutableStateOf(false) }
    var gitIncludeUntracked by remember(mode, sessionId) { mutableStateOf(false) }
    var gitLoading by remember(mode, sessionId) { mutableStateOf(false) }
    var gitError by remember(mode, sessionId) { mutableStateOf<String?>(null) }
    var gitRepository by remember(mode, sessionId) { mutableStateOf(".") }
    var gitBranches by remember(mode, sessionId) { mutableStateOf(emptyList<String>()) }
    var gitBranchQuery by remember(mode, sessionId) { mutableStateOf("") }
    var gitSelectedRef by remember(mode, sessionId) { mutableStateOf<String?>(null) }
    var gitResult by remember(mode, sessionId) { mutableStateOf<LocalRemoteGitDiffUi?>(null) }
    var codeReview by remember(mode, sessionId) { mutableStateOf<com.labteto.dshmobile.local.work.LocalIndependentCodeReview?>(null) }
    var codeReviewBusy by remember(mode, sessionId) { mutableStateOf(false) }
    var codeReviewError by remember(mode, sessionId) { mutableStateOf<String?>(null) }
    var codeReviewConfirm by remember(mode, sessionId) { mutableStateOf(false) }
    var securityReview by remember(mode, sessionId) { mutableStateOf<com.labteto.dshmobile.local.work.LocalSecurityReview?>(null) }
    var securityReviewBusy by remember(mode, sessionId) { mutableStateOf(false) }
    var securityReviewError by remember(mode, sessionId) { mutableStateOf<String?>(null) }
    var securityReviewConfirm by remember(mode, sessionId) { mutableStateOf(false) }
    var gitJob by remember(mode, sessionId) { mutableStateOf<Job?>(null) }
    val chooserTitle = stringResource(R.string.local_files_share_chooser)
    val failedText = stringResource(R.string.local_files_share_failed)
    val limitText = stringResource(R.string.local_files_share_limit, MAX_LOCAL_SHARE_FILES)
    val shareableFiles = remember(mode, section, entries, workspace, conversation, query) {
        val normalizedQuery = query.trim()
        val candidates = when (mode) {
            LocalFilesMode.WORKSPACE -> when {
                normalizedQuery.isNotBlank() -> workspace
                section == -1 -> emptyList()
                else -> entries.filterNot(LocalWorkspaceDirectoryEntry::isDirectory).map {
                    LocalWorkspaceFile(it.path, it.bytes, it.modifiedAt)
                }
            }
            LocalFilesMode.CONVERSATION ->
                (conversation.artifacts + conversation.involved).distinctBy { it.path }
        }
        candidates.filter { file ->
            isShareableWorkspacePath(file.path) &&
                (mode == LocalFilesMode.WORKSPACE ||
                    normalizedQuery.isBlank() || file.path.contains(normalizedQuery, ignoreCase = true))
        }
    }
    val visibleSharePaths = remember(shareableFiles) {
        shareableFiles.map { it.path }.distinct()
    }

    LaunchedEffect(visibleSharePaths) {
        selected.retainAll(visibleSharePaths.toSet())
    }

    fun toggle(path: String) {
        if (path !in visibleSharePaths || !isShareableWorkspacePath(path)) return
        if (path in selected) selected.remove(path)
        else if (selected.size < MAX_LOCAL_SHARE_FILES) selected.add(path)
        else shareError = limitText
    }

    fun share(paths: List<String>) {
        if (sharing || paths.isEmpty()) return
        sharing = true
        shareError = null
        scope.launch {
            try {
                val intent = withContext(Dispatchers.IO) {
                    createLocalWorkspaceShareIntent(context, workspacePath, paths)
                }
                context.startActivity(Intent.createChooser(intent, chooserTitle))
                selected.clear()
                selecting = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                shareError = failedText
            } finally {
                sharing = false
            }
        }
    }

    fun save(paths: List<String>) {
        if (saving || paths.isEmpty()) return
        saving = true
        shareError = null
        savedLocation = null
        scope.launch {
            val exported = mutableListOf<String>()
            try {
                for (path in paths.distinct()) exported += saveToDownloads(path)
                savedLocation = if (exported.size == 1) {
                    "已保存：${exported.single()}"
                } else {
                    "已保存 ${exported.size} 个文件到 Download/神言神语"
                }
                selected.clear()
                selecting = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                shareError = "已保存 ${exported.size} 个文件；剩余文件保存失败：${failure.message.orEmpty()}"
            } finally {
                saving = false
            }
        }
    }

    fun openDownloads() {
        try {
            context.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (failure: android.content.ActivityNotFoundException) {
            shareError = "当前设备没有可打开下载目录的应用"
        }
    }

    /** Review current tracked edits directly, even when the repo has no remote. */
    fun openLocalGitComparison() {
        val compare = compareLocalGit ?: return
        gitJob?.cancel()
        gitRepository = directory.ifBlank { "." }
        gitIsLocal = true
        gitIsCommit = false
        gitSheetOpen = true
        gitBranches = emptyList()
        gitSelectedRef = null
        gitResult = null
        gitError = null
        codeReview = null
        codeReviewError = null
        securityReview = null
        securityReviewError = null
        gitJob = scope.launch {
            gitLoading = true
            try {
                val diff = compare(gitRepository, gitIncludeUntracked)
                gitResult = diff
                if (!diff.truncated && diff.patch.isNotBlank()) {
                    try {
                        codeReview = findIndependentCodeReview?.invoke(sessionId, gitRepository, diff)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        codeReviewError = failure.message ?: "读取历史代码审查记录失败"
                    }
                    try {
                        securityReview = findIndependentSecurityReview?.invoke(sessionId, gitRepository, diff)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        securityReviewError = failure.message ?: "读取历史安全审查记录失败"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                gitError = failure.message ?: "读取本地 Git 修改失败"
            } finally {
                gitLoading = false
            }
        }
    }

    fun openGitComparison() {
        val listBranches = remoteGitBranches ?: return
        gitJob?.cancel()
        gitRepository = directory.ifBlank { "." }
        gitIsLocal = false
        gitIsCommit = false
        gitSheetOpen = true
        gitError = null
        gitSelectedRef = null
        gitResult = null
        codeReview = null
        codeReviewError = null
        securityReview = null
        securityReviewError = null
        gitBranches = emptyList()
        gitBranchQuery = ""
        gitJob = scope.launch {
            gitLoading = true
            try {
                gitBranches = listBranches(gitRepository)
                if (gitBranches.isEmpty()) {
                    gitError = "当前目录没有可比较的本地远端跟踪分支。请先在该 Git 仓库中获取远端分支。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                gitError = failure.message ?: "读取本地 Git 分支失败"
            } finally {
                gitLoading = false
            }
        }
    }

    fun compareGitReference(reference: String) {
        val compare = compareRemoteGit ?: return
        if (gitLoading || reference !in gitBranches) return
        val remote = reference.substringBefore('/')
        val branch = reference.substringAfter('/')
        gitJob?.cancel()
        gitJob = scope.launch {
            gitLoading = true
            gitSelectedRef = reference
            gitResult = null
            codeReview = null
            codeReviewError = null
            securityReview = null
            securityReviewError = null
            gitError = null
            try {
                val diff = compare(gitRepository, remote, branch)
                gitResult = diff
                // Reopening the same diff reuses its durable report instead of charging for another review.
                if (!diff.truncated && diff.patch.isNotBlank()) {
                    try {
                        codeReview = findIndependentCodeReview?.invoke(sessionId, gitRepository, diff)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        codeReviewError = failure.message ?: "读取历史代码审查记录失败"
                    }
                    try {
                        securityReview = findIndependentSecurityReview?.invoke(sessionId, gitRepository, diff)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        securityReviewError = failure.message ?: "读取历史安全审查记录失败"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                gitError = failure.message ?: "比较远端分支失败"
            } finally {
                gitLoading = false
            }
        }
    }


    /** A single advanced target field, using the same review and approval pipeline as other diffs. */
    fun openCommitComparison() {
        if (compareCommitGit == null) return
        gitJob?.cancel()
        gitRepository = directory.ifBlank { "." }
        gitIsLocal = false
        gitIsCommit = true
        gitSheetOpen = true
        gitCommitInput = ""
        gitBranches = emptyList()
        gitSelectedRef = null
        gitResult = null
        gitError = null
        codeReview = null
        codeReviewError = null
        securityReview = null
        securityReviewError = null
    }

    fun loadCommitComparison() {
        val compare = compareCommitGit ?: return
        val commit = gitCommitInput.trim()
        if (commit.isEmpty()) {
            gitError = "请粘贴要审查的 Git 提交哈希"
            return
        }
        gitJob?.cancel()
        gitJob = scope.launch {
            gitLoading = true
            gitError = null
            gitResult = null
            gitSelectedRef = null
            codeReview = null
            codeReviewError = null
            securityReview = null
            securityReviewError = null
            try {
                val diff = compare(gitRepository, commit)
                gitResult = diff
                gitSelectedRef = diff.trackingRef
                if (!diff.truncated && diff.patch.isNotBlank()) {
                    try {
                        codeReview = findIndependentCodeReview?.invoke(sessionId, gitRepository, diff)
                        securityReview = findIndependentSecurityReview?.invoke(sessionId, gitRepository, diff)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        codeReviewError = failure.message ?: "读取历史审查失败"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                gitError = failure.message ?: "读取指定提交失败"
            } finally {
                gitLoading = false
            }
        }
    }

    val readFilesFailed = stringResource(R.string.local_files_read_failed)
    val previewFailed = stringResource(R.string.local_files_preview_failed)

    suspend fun openPreview(path: String) {
        invalidatePreview()
        previewPreviousOffsets.clear()
        val epoch = previewEpoch
        previewJob = currentCoroutineContext()[Job]
        previewPath = path
        preview = null
        previewLoading = true
        error = null
        try {
            val loaded = loadPreview(path)
            if (previewEpoch == epoch) preview = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (previewEpoch == epoch) {
                error = failure.message ?: previewFailed
                preview = null
                previewPath = null
            }
        } finally {
            if (previewEpoch == epoch) {
                previewLoading = false
                previewJob = null
            }
        }
    }

    suspend fun pagePreview(offset: Long, backward: Boolean) {
        val path = previewPath ?: return
        val previous = preview ?: return
        invalidatePreview()
        val epoch = previewEpoch
        previewJob = currentCoroutineContext()[Job]
        previewLoading = true
        error = null
        try {
            val loaded = loadPreviewPage(path, offset)
            if (previewEpoch == epoch) {
                require(loaded.offset == offset && loaded.file.path == previous.file.path &&
                    loaded.file.bytes == previous.file.bytes &&
                    loaded.file.modifiedAt == previous.file.modifiedAt) {
                    "文件预览内容已经发生变化，请刷新后重试"
                }
                if (backward) {
                    if (previewPreviousOffsets.isNotEmpty()) previewPreviousOffsets.removeAt(previewPreviousOffsets.lastIndex)
                } else previewPreviousOffsets.add(previous.offset)
                preview = loaded
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (previewEpoch == epoch) error = failure.message ?: previewFailed
        } finally {
            if (previewEpoch == epoch) {
                previewLoading = false
                previewJob = null
            }
        }
    }

    suspend fun reload() {
        invalidatePreview()
        val epoch = loadEpoch + 1
        loadEpoch = epoch
        val pageDirectory = directory
        val currentQuery = query.trim()
        val currentSection = section
        fun stillCurrent(): Boolean =
            loadEpoch == epoch && directory == pageDirectory && section == currentSection &&
                query.trim() == currentQuery
        selected.clear()
        selecting = false
        loading = true
        loadingNextPage = false
        loadingNextSearchPage = false
        nextSearchOffset = null
        pageReadError = null
        searchPageError = null
        error = null
        preview = null
        try {
            when (mode) {
                LocalFilesMode.WORKSPACE -> {
                    if (currentQuery.isNotEmpty()) {
                        val page = loadSearchPage(currentQuery, 0)
                        if (stillCurrent()) {
                            workspace = page.files
                            nextSearchOffset = page.nextOffset
                        }
                    } else {
                        val page = if (currentSection == -1) LocalWorkspaceDirectoryPage(emptyList(), null)
                            else loadDirectoryPage(pageDirectory.ifBlank { "." }, 0)
                        if (stillCurrent()) {
                            entries = page.entries
                            nextDirectoryOffset = page.nextOffset
                        }
                    }
                }
                LocalFilesMode.CONVERSATION -> {
                    val found = loadConversation(sessionId)
                    if (stillCurrent()) conversation = found
                }
            }
            if (stillCurrent()) {
                lastLoadedSearch = currentQuery
                previewPath?.let { path -> openPreview(path) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (stillCurrent()) {
                error = failure.message ?: if (previewPath == null) readFilesFailed else previewFailed
                preview = null
                previewPath = null
            }
        } finally {
            if (stillCurrent()) loading = false
        }
    }

    fun fetchNextDirectoryPage() {
        val offset = nextDirectoryOffset ?: return
        if (loading || loadingNextPage || pageReadError != null || query.isNotBlank() ||
            mode != LocalFilesMode.WORKSPACE || section < 0) return
        val epoch = loadEpoch
        val currentDirectory = directory
        loadingNextPage = true
        scope.launch {
            try {
                val page = loadDirectoryPage(currentDirectory.ifBlank { "." }, offset)
                if (loadEpoch == epoch && directory == currentDirectory && query.isBlank()) {
                    entries = entries + page.entries
                    nextDirectoryOffset = page.nextOffset
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (loadEpoch == epoch) pageReadError = failure.message ?: readFilesFailed
            } finally {
                if (loadEpoch == epoch) loadingNextPage = false
            }
        }
    }

    fun fetchNextSearchPage() {
        val offset = nextSearchOffset ?: return
        val search = query.trim()
        if (loading || loadingNextSearchPage || searchPageError != null || search.isEmpty() ||
            mode != LocalFilesMode.WORKSPACE) return
        val epoch = loadEpoch
        loadingNextSearchPage = true
        scope.launch {
            try {
                val page = loadSearchPage(search, offset)
                if (loadEpoch == epoch && query.trim() == search) {
                    workspace = workspace + page.files
                    nextSearchOffset = page.nextOffset
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (loadEpoch == epoch) searchPageError = failure.message ?: readFilesFailed
            } finally {
                if (loadEpoch == epoch) loadingNextSearchPage = false
            }
        }
    }

    // Watch only the selected directory or the bounded paths visible in search results.
    // Changes trigger a fresh Owner query; the file watcher is never a second file catalog.
    val observedPaths = if (mode != LocalFilesMode.WORKSPACE || section < 0 && query.isBlank()) {
        emptyList()
    } else if (query.isNotBlank()) {
        listOf(".") + workspace.take(64).map(LocalWorkspaceFile::path)
    } else {
        listOf(directory.ifBlank { "." })
    }
    LaunchedEffect(mode, sessionId, directory, query, observedPaths) {
        if (observedPaths.isEmpty()) return@LaunchedEffect
        try {
            observeChanges(observedPaths).collectLatest {
                delay(300)
                if (previewPath == null && !loading) reload()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (mode == LocalFilesMode.WORKSPACE) {
                pageReadError = failure.message ?: "文件变化监听失败，请手动刷新"
            }
        }
    }

    // Search is debounced and its ranked snapshot belongs to this exact visible query.
    // Cancelling the effect on query change or closing the dialog releases that snapshot.
    LaunchedEffect(mode, sessionId, query) {
        val activeQuery = query.trim()
        try {
            if (mode == LocalFilesMode.WORKSPACE && loadedForEntry &&
                activeQuery != lastLoadedSearch) {
                if (activeQuery.isNotEmpty()) kotlinx.coroutines.delay(280)
                reload()
            }
            if (mode == LocalFilesMode.WORKSPACE && activeQuery.isNotEmpty()) awaitCancellation()
        } finally {
            if (mode == LocalFilesMode.WORKSPACE && activeQuery.isNotEmpty()) {
                withContext(NonCancellable + Dispatchers.IO) { stopSearch(activeQuery) }
            }
        }
    }

    LaunchedEffect(mode, sessionId, initialFilePath, entryRequest) {
        if (initialFilePath == null && loadedForEntry) return@LaunchedEffect
        if (initialFilePath != null) {
            previewPath = initialFilePath
            selecting = false
            selected.clear()
        }
        reload()
        loadedForEntry = true
        initialFilePath?.let(onInitialFilePathConsumed)
    }

    fun navigateBack() {
        invalidatePreview()
        when {
            selecting -> {
                selecting = false
                selected.clear()
                shareError = null
            }
            preview != null || previewPath != null -> {
                preview = null
                previewPath = null
                error = null
            }
            query.isNotBlank() -> query = ""
            mode == LocalFilesMode.WORKSPACE && section >= 0 -> {
                if (directory.isNotEmpty() &&
                    directory != "任务" && directory != "交付成果") {
                    directory = directory.substringBeforeLast('/', "")
                } else {
                    directory = ""
                    section = -1
                }
                loading = true
                scope.launch { reload() }
            }
            else -> onDismiss()
        }
    }

    val hasInternalBackLayer =
        preview != null ||
            previewPath != null ||
            (mode == LocalFilesMode.WORKSPACE && section >= 0) ||
            selecting || query.isNotBlank()
    BackHandler(enabled = hasInternalBackLayer, onBack = ::navigateBack)

    Surface(Modifier.fillMaxSize(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.rootSurface()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                val headerTitle = when {
                    preview != null -> preview?.file?.path?.substringAfterLast('/').orEmpty()
                    mode == LocalFilesMode.WORKSPACE && directory.contains('/') ->
                        directory.substringAfterLast('/')
                    mode == LocalFilesMode.WORKSPACE && section == 0 ->
                        stringResource(R.string.local_workspace_tasks)
                    mode == LocalFilesMode.WORKSPACE && section == 1 ->
                        stringResource(R.string.local_workspace_deliverables)
                    mode == LocalFilesMode.WORKSPACE && section == 2 ->
                        stringResource(R.string.local_workspace_other_files)
                    mode == LocalFilesMode.WORKSPACE -> stringResource(R.string.app_project_title)
                    else -> stringResource(R.string.local_files_conversation_title)
                }
                val headerSubtitle = when {
                    preview != null -> preview?.file?.path
                    mode == LocalFilesMode.WORKSPACE && section >= 0 ->
                        directory.takeIf(String::isNotEmpty)?.replace("/", " / ")
                            ?: stringResource(R.string.app_project_title)
                    else -> null
                }
                SyTopBar(
                    title = headerTitle,
                    subtitle = headerSubtitle,
                    onBack = ::navigateBack,
                    backContentDescription = stringResource(
                        if (preview != null || previewPath != null || directory.isNotEmpty()) {
                            R.string.local_files_back_to_files
                        } else {
                            R.string.local_files_back
                        },
                    ),
                    modifier = Modifier.let { base ->
                        val selectedPath = previewPath ?: initialFilePath
                        if (!selectedPath.isNullOrBlank()) {
                            base.localArtifactSharedBounds(workspacePath, selectedPath)
                        } else base
                    },
                    largeTitle = preview == null && directory.isEmpty() &&
                        (mode != LocalFilesMode.WORKSPACE || section == -1),
                    actionIcon = FeatherIcons.RefreshCw,
                    actionContentDescription = stringResource(R.string.local_files_refresh),
                    actionEnabled = !loading && preview == null,
                    onAction = { scope.launch { reload() } },
                )

                if (preview == null) SySearchField(query, {
                    invalidatePreview()
                    previewPath = null
                    query = it
                },
                    placeholder = stringResource(R.string.fluid_files_search),
                    modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd))

                if (mode == LocalFilesMode.WORKSPACE && section == -1 &&
                    preview == null && query.isBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapLg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                    ) {
                        Text("完成的成果：手机存储/Download/神言神语",
                            modifier = Modifier.weight(1f),
                            style = DsType.small13.withReadingWeight())
                        SyButton(
                            text = "打开下载",
                            onClick = ::openDownloads,
                            size = SyButtonSize.Small,
                            variant = SyButtonVariant.Outline,
                        )
                    }
                }
                if (preview == null && query.isNotBlank() && mode == LocalFilesMode.WORKSPACE) {
                    Text(
                        stringResource(R.string.local_workspace_search_scope),
                        modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapXs),
                        style = DsType.caption11.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                    )
                }

                if (mode == LocalFilesMode.WORKSPACE && preview == null && !selecting && projectActions != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapLg),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        SyButton(
                            text = "管理项目",
                            onClick = { projectsSheetOpen = true },
                            enabled = !loading,
                            size = SyButtonSize.Small,
                            variant = SyButtonVariant.Outline,
                            modifier = Modifier.testTag("work_projects_open"),
                        )
                    }
                }
                if (mode == LocalFilesMode.WORKSPACE && preview == null && !selecting &&
                    (compareLocalGit != null || compareCommitGit != null ||
                        (remoteGitBranches != null && compareRemoteGit != null))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapLg),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        SyButton(
                            text = "代码审查",
                            onClick = {
                                when {
                                    compareLocalGit != null -> openLocalGitComparison()
                                    remoteGitBranches != null && compareRemoteGit != null -> openGitComparison()
                                    compareCommitGit != null -> openCommitComparison()
                                }
                            },
                            enabled = !loading,
                            size = SyButtonSize.Small,
                            variant = SyButtonVariant.Outline,
                            modifier = Modifier.testTag("work_git_review_entry"),
                        )
                    }
                }
                if (preview == null && !loading && error == null && visibleSharePaths.isNotEmpty()) {
                    if (selecting) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapLg),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                        ) {
                            Text(
                                stringResource(R.string.local_files_selected_count, selected.size),
                                modifier = Modifier.weight(1f),
                                style = DsType.small13Strong.withReadingWeight(),
                                color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
                            )
                            SyButton(
                                text = stringResource(R.string.common_cancel),
                                onClick = { selecting = false; selected.clear(); shareError = null },
                                size = SyButtonSize.Small,
                                variant = SyButtonVariant.Ghost,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(
                                horizontal = SyMetric.gapLg, vertical = SyMetric.gapSm,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                        ) {
                            SyButton(
                                text = stringResource(R.string.local_files_select_all),
                                onClick = {
                                    selected.clear()
                                    selected.addAll(visibleSharePaths.take(MAX_LOCAL_SHARE_FILES))
                                    shareError = if (visibleSharePaths.size > MAX_LOCAL_SHARE_FILES) limitText else null
                                },
                                modifier = Modifier.weight(1f),
                                size = SyButtonSize.Small,
                                variant = SyButtonVariant.Outline,
                            )
                            SyButton(
                                text = stringResource(R.string.local_files_share_selected),
                                onClick = { share(selected.toList()) },
                                modifier = Modifier.weight(1f),
                                enabled = selected.isNotEmpty() && !sharing && !saving,
                                size = SyButtonSize.Small,
                            )
                            SyButton(
                                text = "保存到下载",
                                onClick = { save(selected.toList()) },
                                modifier = Modifier.weight(1f),
                                enabled = selected.isNotEmpty() && !sharing && !saving,
                                size = SyButtonSize.Small,
                                variant = SyButtonVariant.Outline,
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapLg),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            SyButton(
                                text = stringResource(R.string.local_files_select),
                                onClick = { selecting = true },
                                size = SyButtonSize.Small,
                                variant = SyButtonVariant.Ghost,
                            )
                        }
                    }
                }
                if (preview != null && isShareableWorkspacePath(preview!!.file.path)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapLg),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        SyButton(
                            text = stringResource(R.string.local_files_share_one),
                            onClick = { share(listOf(preview!!.file.path)) },
                            enabled = !sharing && !saving,
                            size = SyButtonSize.Small,
                        )
                        SyButton(
                            text = "保存到下载",
                            onClick = { save(listOf(preview!!.file.path)) },
                            enabled = !saving && !sharing,
                            size = SyButtonSize.Small,
                            variant = SyButtonVariant.Outline,
                        )
                    }
                }
                savedLocation?.let { location ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapLg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                    ) {
                        Text(location, modifier = Modifier.weight(1f),
                            style = DsType.small13.withReadingWeight())
                        SyButton(
                            text = "查看下载",
                            onClick = ::openDownloads,
                            size = SyButtonSize.Small,
                            variant = SyButtonVariant.Ghost,
                        )
                    }
                }
                shareError?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapSm),
                        style = DsType.small13.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.dangerInk,
                    )
                }

                CompositionLocalProvider(
                    LocalFileSelectionUi provides FileSelectionUi(
                        active = selecting,
                        selected = selected.toSet(),
                        toggle = ::toggle,
                        begin = { path -> selecting = true; toggle(path) },
                    ),
                ) {
                when {
                    loading -> {
                        SyLoadingState(
                            icon = FeatherIcons.Folder,
                            label = stringResource(R.string.local_files_loading),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    error != null -> {
                        SyEmptyState(
                            icon = FeatherIcons.AlertTriangle,
                            title = readFilesFailed,
                            body = error.orEmpty(),
                            actionText = stringResource(R.string.common_retry),
                            onAction = { scope.launch { reload() } },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    preview != null -> LocalFilePreviewBody(
                        preview = preview!!,
                        loadingPage = previewLoading,
                        hasPrevious = previewPreviousOffsets.isNotEmpty(),
                        onPrevious = { previewPreviousOffsets.lastOrNull()?.let { offset -> scope.launch {
                            pagePreview(offset, backward = true)
                        } } },
                        onNext = { preview?.nextOffset?.let { offset -> scope.launch {
                            pagePreview(offset, backward = false)
                        } } },
                    )
                    query.isNotBlank() -> {
                        if (shareableFiles.isEmpty() && nextSearchOffset == null) LocalFilesEmpty(
                            stringResource(R.string.fluid_files_no_matches), query.trim())
                        else LocalFlatFileList(
                            files = shareableFiles,
                            searchKey = query.trim(),
                            hasMore = nextSearchOffset != null && mode == LocalFilesMode.WORKSPACE,
                            loadingMore = loadingNextSearchPage,
                            pageError = searchPageError,
                            onPrefetch = ::fetchNextSearchPage,
                            onRetry = { scope.launch { reload() } },
                        ) { file -> openPreview(file.path) }
                    }
                    mode == LocalFilesMode.WORKSPACE -> {
                        if (section == -1) {
                            LocalWorkspaceRootList { category ->
                                section = category
                                directory = when (category) {
                                    0 -> "任务"
                                    1 -> "交付成果"
                                    else -> ""
                                }
                                loading = true
                                scope.launch { reload() }
                            }
                        } else {
                            LocalWorkspaceDirectoryList(
                                directoryKey = directory,
                                entries = if (section == 2 && directory.isEmpty())
                                    entries.filterNot { it.path == "任务" || it.path == "交付成果" }
                                else entries,
                                localizedInternalName = section == 2 && directory.isEmpty(),
                                hasMore = nextDirectoryOffset != null,
                                loadingMore = loadingNextPage,
                                pageError = pageReadError,
                                onPrefetch = ::fetchNextDirectoryPage,
                                onRetry = { scope.launch { reload() } },
                                section = section,
                                isCategoryRoot = directory.isEmpty() || directory == "任务" || directory == "交付成果",
                                onDirectory = {
                                    directory = it
                                    loading = true
                                    scope.launch { reload() }
                                },
                            ) { file -> openPreview(file.path) }
                        }
                    }
                    conversation.isEmpty -> LocalFilesEmpty(
                        title = stringResource(R.string.local_empty_conversation_files_title),
                        body = stringResource(R.string.local_empty_conversation_files_body),
                    )
                    else -> ConversationLocalFileList(conversation) { file ->
                                openPreview(file.path)
                    }
                }

                }

                if (previewLoading) {
                    Text(
                        stringResource(R.string.local_files_loading),
                        modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                    )
                }
            }
        }
    if (projectsSheetOpen && mode == LocalFilesMode.WORKSPACE && projectActions != null) {
        LocalWorkProjectsSheet(
            actions = projectActions,
            sessionId = sessionId,
            currentFolder = directory.takeIf { it.isNotBlank() && it != "任务" && it != "交付成果" } ?: ".",
            onOpenProject = { projectRoot ->
                projectsSheetOpen = false
                query = ""
                directory = if (projectRoot == ".") "" else projectRoot
                section = when {
                    projectRoot == "." -> -1
                    projectRoot == "任务" || projectRoot.startsWith("任务/") -> 0
                    projectRoot == "交付成果" || projectRoot.startsWith("交付成果/") -> 1
                    else -> 2
                }
                loading = true
                scope.launch { reload() }
            },
            onDismiss = { projectsSheetOpen = false },
        )
    }
    if (codeReviewConfirm && codeReview != null && gitResult != null) {
        SyResponsiveSheet(
            title = "确认接纳审查结论",
            subtitle = "只保存人工接纳记录，不执行修改，也不会把审查意见当作测试通过。",
            onDismiss = { if (!codeReviewBusy) codeReviewConfirm = false },
        ) {
            SyButton(
                text = "确认接纳",
                enabled = !codeReviewBusy,
                onClick = {
                    val saved = codeReview
                    val diff = gitResult
                    val accept = acceptIndependentCodeReview
                    if (saved != null && diff != null && accept != null) {
                        gitJob?.cancel()
                        gitJob = scope.launch {
                            codeReviewBusy = true
                            codeReviewError = null
                            try {
                                if (accept(sessionId, gitRepository, saved, diff)) {
                                    codeReview = saved.copy(humanAccepted = true)
                                    codeReviewConfirm = false
                                } else {
                                    codeReviewError = "Git 差异或审查版本已变化，请重新审查。"
                                    codeReviewConfirm = false
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                codeReviewError = failure.message ?: "接纳审查结论失败"
                                codeReviewConfirm = false
                            } finally {
                                codeReviewBusy = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SyButton("取消", onClick = { codeReviewConfirm = false }, enabled = !codeReviewBusy,
                variant = SyButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
        }
    }
    if (securityReviewConfirm && securityReview != null && gitResult != null) {
        SyResponsiveSheet(
            title = "确认安全放行",
            subtitle = "仅批准此份 Git 变更的安全审核结论；变更后自动失效。工具执行仍需原有权限与审批。",
            onDismiss = { if (!securityReviewBusy) securityReviewConfirm = false },
        ) {
            SyButton(
                text = "批准当前变更",
                enabled = !securityReviewBusy,
                onClick = {
                    val saved = securityReview
                    val diff = gitResult
                    val approve = approveIndependentSecurityReview
                    if (saved != null && saved.verdict == "allow" && diff != null && approve != null) {
                        gitJob?.cancel()
                        gitJob = scope.launch {
                            securityReviewBusy = true
                            securityReviewError = null
                            try {
                                if (approve(sessionId, gitRepository, saved, diff)) {
                                    securityReview = saved.copy(humanApproved = true)
                                } else {
                                    securityReviewError = "代码、风险裁决或审批版本已变化，请重新安全审查。"
                                }
                                securityReviewConfirm = false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                securityReviewError = failure.message ?: "安全放行记录失败"
                                securityReviewConfirm = false
                            } finally {
                                securityReviewBusy = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("work_security_review_approve_confirm"),
            )
            SyButton("取消", onClick = { securityReviewConfirm = false },
                enabled = !securityReviewBusy, variant = SyButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth())
        }
    }
    if (gitSheetOpen) {
        val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
        SyResponsiveSheet(
            title = when {
                gitIsCommit -> "审查指定提交"
                gitIsLocal -> "检查本地修改"
                else -> "比较远端分支"
            },
            subtitle = when {
                gitIsCommit -> "审查已存在的 Git 提交，合并提交使用第一父提交作为基线；不联网"
                gitIsLocal -> "包括暂存与未暂存的已跟踪文件，不联网，不自动提交"
                else -> "只读取已获取的本地远端分支，不会自动联网"
            },
            onDismiss = { gitJob?.cancel(); gitSheetOpen = false },
            scrollable = true,
        ) {
            Text("审查范围", style = DsType.small13Strong, color = colors.text)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                if (compareLocalGit != null) SyButton(
                    text = "本地修改",
                    onClick = ::openLocalGitComparison,
                    enabled = !gitLoading && !codeReviewBusy && !securityReviewBusy,
                    size = SyButtonSize.Small,
                    variant = if (gitIsLocal) SyButtonVariant.Outline else SyButtonVariant.Ghost,
                    modifier = Modifier.testTag("work_local_git_diff"),
                )
                if (remoteGitBranches != null && compareRemoteGit != null) SyButton(
                    text = "远端分支",
                    onClick = ::openGitComparison,
                    enabled = !gitLoading && !codeReviewBusy && !securityReviewBusy,
                    size = SyButtonSize.Small,
                    variant = if (!gitIsLocal && !gitIsCommit) SyButtonVariant.Outline else SyButtonVariant.Ghost,
                    modifier = Modifier.testTag("work_git_remote_diff"),
                )
                if (compareCommitGit != null) SyButton(
                    text = "指定提交",
                    onClick = ::openCommitComparison,
                    enabled = !gitLoading && !codeReviewBusy && !securityReviewBusy,
                    size = SyButtonSize.Small,
                    variant = if (gitIsCommit) SyButtonVariant.Outline else SyButtonVariant.Ghost,
                    modifier = Modifier.testTag("work_git_commit_review_open"),
                )
            }
            Text("仓库目录：$gitRepository", style = DsType.small13, color = colors.textSecondary)
            if (gitIsCommit && compareCommitGit != null) {
                SySearchField(
                    gitCommitInput, { gitCommitInput = it.take(64) },
                    placeholder = "粘贴 Git 提交哈希",
                    modifier = Modifier.fillMaxWidth().testTag("work_git_commit_input"),
                )
                SyButton(
                    text = if (gitLoading) "正在读取…" else "读取提交差异",
                    onClick = ::loadCommitComparison,
                    enabled = !gitLoading && gitCommitInput.trim().length >= 7,
                    modifier = Modifier.fillMaxWidth().testTag("work_git_commit_load"),
                )
                Text("只接受仓库中已有的提交哈希，支持首次提交；提交内容变化时历史审查不会误复用。",
                    style = DsType.caption11, color = colors.textSecondary)
            }
            if (gitIsLocal && compareLocalGit != null) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                    SyCheckboxControl(
                        checked = gitIncludeUntracked,
                        onCheckedChange = { selected ->
                            if (!gitLoading && !codeReviewBusy) {
                                gitIncludeUntracked = selected
                                codeReview = null
                                securityReview = null
                                openLocalGitComparison()
                            }
                        },
                    )
                    Text("同时审查新建文件", style = DsType.small13, color = colors.text,
                        modifier = Modifier.testTag("work_git_include_untracked"))
                }
                gitResult?.let { snapshot ->
                    if (snapshot.untrackedFileCount > 0) Text(
                        "发现 " + snapshot.untrackedFileCount + " 个未跟踪文件。新文件可能包含密钥；选择后才会纳入模型审查。",
                        style = DsType.caption11, color = colors.warningInk)
                }
            }
            if (gitLoading) Text("正在读取 Git 结果…", color = colors.textSecondary)
            gitError?.let { Text(it, color = colors.dangerInk) }
            if (gitBranches.size > 8) SySearchField(
                gitBranchQuery, { gitBranchQuery = it },
                placeholder = "搜索远端分支",
                modifier = Modifier.fillMaxWidth().testTag("work_git_branch_search"),
            )
            val visibleGitBranches = gitBranches.filter {
                gitBranchQuery.isBlank() || it.contains(gitBranchQuery.trim(), ignoreCase = true)
            }
            visibleGitBranches.take(20).forEach { reference ->
                SyButton(
                    text = if (gitSelectedRef == reference) "已选择 · $reference" else reference,
                    onClick = { compareGitReference(reference) },
                    enabled = !gitLoading,
                    variant = SyButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (visibleGitBranches.size > 20) Text(
                "还有 ${visibleGitBranches.size - 20} 条分支，请缩小搜索范围。",
                style = DsType.caption11, color = colors.textSecondary,
            )
            gitResult?.let { result ->
                Text(result.trackingRef, style = DsType.small13Strong, color = colors.text)
                SelectionContainer {
                    Text(result.statistics.ifBlank { "没有已跟踪文件的变化" },
                        style = DsType.small13, color = colors.textSecondary)
                }
                if (result.patch.isNotEmpty()) {
                    Text("差异预览", style = DsType.small13Strong, color = colors.text)
                    LocalGitDiffPreview(result.patch)
                    SyButton(
                        text = "复制差异内容",
                        onClick = { clipboard.setText(AnnotatedString(result.patch)) },
                        variant = SyButtonVariant.Outline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (result.truncated) {
                    Text("差异超过 128KB，当前结果仅包含前 128KB。",
                        style = DsType.caption11, color = colors.warningInk)
                }
                if (mode == LocalFilesMode.WORKSPACE && result.patch.isNotBlank() &&
                    (runIndependentCodeReview != null || codeReview != null || codeReviewError != null)) {
                    // A durable review remains readable even without a model-run capability.
                    if (runIndependentCodeReview != null) SyButton(
                        text = if (codeReviewBusy) "独立审查中…" else "独立代码审查",
                        onClick = {
                            val review = runIndependentCodeReview
                            if (review != null && !codeReviewBusy && !securityReviewBusy) {
                                gitJob?.cancel()
                                gitJob = scope.launch {
                                    codeReviewBusy = true
                                    codeReviewError = null
                                    codeReview = null
                                    try {
                                        codeReview = review(sessionId, gitRepository, result)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        codeReviewError = failure.message ?: "独立代码审查失败"
                                    } finally {
                                        codeReviewBusy = false
                                    }
                                }
                            }
                        },
                        enabled = !gitLoading && !codeReviewBusy && !securityReviewBusy && !result.truncated,
                        modifier = Modifier.fillMaxWidth().testTag("work_code_review_start"),
                    )
                    codeReviewError?.let { Text(it, color = colors.dangerInk) }
                    codeReview?.let { saved ->
                        Text("审查结论 · " + saved.trackingRef,
                            style = DsType.small13Strong, color = colors.text)
                        SelectionContainer {
                            Column(
                                Modifier.fillMaxWidth().heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                MarkdownText(saved.report,
                                    modifier = Modifier.testTag("work_code_review_report"))
                            }
                        }
                        Text("只读模型审查独立留痕；人工接纳仅确认已阅读此份结论。",
                            style = DsType.caption11, color = colors.textSecondary)
                        if (!saved.humanAccepted && acceptIndependentCodeReview != null) {
                            SyButton(
                                text = "人工接纳审查结论",
                                onClick = { codeReviewConfirm = true },
                                enabled = !codeReviewBusy,
                                modifier = Modifier.fillMaxWidth().testTag("work_code_review_accept"),
                                variant = SyButtonVariant.Outline,
                            )
                        } else if (saved.humanAccepted) {
                            Text("人工接纳已记录", color = colors.textSecondary)
                        }
                    }
                }
                if (mode == LocalFilesMode.WORKSPACE && result.patch.isNotBlank() &&
                    (runIndependentSecurityReview != null || securityReview != null ||
                        securityReviewError != null)) {
                    // Risk evidence and prior user decisions must survive a temporarily unavailable reviewer.
                    if (runIndependentSecurityReview != null) SyButton(
                        text = if (securityReviewBusy) "安全审查中…" else "独立安全审核",
                        onClick = {
                            val start = runIndependentSecurityReview
                            if (start != null && !securityReviewBusy && !codeReviewBusy) {
                                gitJob?.cancel()
                                gitJob = scope.launch {
                                    securityReviewBusy = true
                                    securityReviewError = null
                                    securityReview = null
                                    try {
                                        securityReview = start(sessionId, gitRepository, result)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        securityReviewError = failure.message ?: "独立安全审核失败"
                                    } finally {
                                        securityReviewBusy = false
                                    }
                                }
                            }
                        },
                        enabled = !gitLoading && !securityReviewBusy && !codeReviewBusy &&
                            !result.truncated,
                        modifier = Modifier.fillMaxWidth().testTag("work_security_review_start"),
                        variant = SyButtonVariant.Outline,
                    )
                    securityReviewError?.let { Text(it, color = colors.dangerInk) }
                    securityReview?.let { saved ->
                        Text("安全审查 · " + saved.trackingRef,
                            style = DsType.small13Strong, color = colors.text)
                        SelectionContainer {
                            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState())) {
                                MarkdownText(saved.report,
                                    modifier = Modifier.testTag("work_security_review_report"))
                            }
                        }
                        if (saved.verdict == "block") {
                            Text("审核存在待处理风险，当前版本不可放行。修复后重新检查。",
                                style = DsType.caption11, color = colors.warningInk)
                        } else if (saved.humanApproved) {
                            Text("当前版本已人工批准；修改代码后需重新审核。",
                                style = DsType.caption11, color = colors.successInk)
                        } else if (approveIndependentSecurityReview != null) {
                            SyButton(
                                text = "人工批准当前变更",
                                onClick = { securityReviewConfirm = true },
                                enabled = !securityReviewBusy && !codeReviewBusy,
                                modifier = Modifier.fillMaxWidth().testTag("work_security_review_approve"),
                                variant = SyButtonVariant.Outline,
                            )
                        }
                        Text("安全审核基于只读 Git 快照；批准记录与代码版本绑定，执行仍需工具权限。",
                            style = DsType.caption11, color = colors.textSecondary)
                    }
                }
                Text(when {
                    gitIsCommit ->
                        "指定提交只包含该次提交的历史差异，不包含当前工作区的未提交修改。"
                    !gitIsLocal ->
                        "远端分支比较包含当前已跟踪文件的工作区变化，不包含未跟踪文件。"
                    result.includesUntrackedFiles ->
                        "已纳入所选新建文件；忽略规则仍生效，超出范围时会停止完整审查。"
                    else ->
                        "当前仅检查已跟踪文件；需要检查新建文件时可开启上方选项。"
                }, style = DsType.caption11, color = colors.textSecondary)
            }
        }
    }
}

/** Virtual root categories; actual file locations never change during an upgrade. */
internal fun workspaceSectionFiles(files: List<LocalWorkspaceFile>, section: Int): List<LocalWorkspaceFile> =
    when (section) {
        0 -> files.filter { it.path.startsWith("任务/") }
        1 -> files.filter { it.path.startsWith("交付成果/") }
        2 -> files.filterNot { it.path.startsWith("任务/") || it.path.startsWith("交付成果/") }
        else -> emptyList()
    }

@Composable
private fun LocalWorkspaceRootList(onOpen: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "workspace-task-folders") {
            LocalDirectoryRow(
                name = stringResource(R.string.local_workspace_tasks),
                subtitle = stringResource(R.string.local_workspace_tasks_hint),
                onClick = { onOpen(0) },
            )
        }
        item(key = "workspace-deliverables") {
            LocalDirectoryRow(
                name = stringResource(R.string.local_workspace_deliverables),
                subtitle = stringResource(R.string.local_workspace_deliverables_hint),
                onClick = { onOpen(1) },
            )
        }
        item(key = "workspace-other-files") {
            LocalDirectoryRow(
                name = stringResource(R.string.local_workspace_other_files),
                subtitle = stringResource(R.string.local_workspace_other_hint),
                onClick = { onOpen(2) },
            )
        }
    }
}

@Composable
private fun LocalWorkspaceDirectoryList(
    directoryKey: String,
    entries: List<LocalWorkspaceDirectoryEntry>,
    localizedInternalName: Boolean,
    hasMore: Boolean,
    loadingMore: Boolean,
    pageError: String?,
    onPrefetch: () -> Unit,
    onRetry: () -> Unit,
    section: Int,
    isCategoryRoot: Boolean,
    onDirectory: (String) -> Unit,
    onOpen: suspend (LocalWorkspaceFile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = remember(directoryKey) { LazyListState() }
    // Prefetch 40 rows ahead; next pages append without replacing the list or a "load more" row.
    LaunchedEffect(directoryKey, entries.size, hasMore, loadingMore, pageError) {
        if (hasMore && !loadingMore && pageError == null) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .first { it >= entries.size - 40 }
            onPrefetch()
        }
    }
    if (entries.isEmpty() && !hasMore) {
        LocalFilesEmpty(
            title = stringResource(
                when {
                    !isCategoryRoot -> R.string.local_empty_workspace_title
                    section == 0 -> R.string.local_workspace_tasks_empty_title
                    section == 1 -> R.string.local_workspace_deliverables_empty_title
                    else -> R.string.local_empty_workspace_title
                }
            ),
            body = stringResource(
                when {
                    !isCategoryRoot -> R.string.local_empty_workspace_body
                    section == 0 -> R.string.local_workspace_tasks_empty_body
                    section == 1 -> R.string.local_workspace_deliverables_empty_body
                    else -> R.string.local_empty_workspace_body
                }
            ),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(entries, key = LocalWorkspaceDirectoryEntry::path) { entry ->
            if (entry.isDirectory) {
                val internal = localizedInternalName && entry.path in setOf(".dsh", ".adsh")
                LocalDirectoryRow(
                    name = if (internal) "应用内部文件（${entry.path}）"
                        else entry.path.substringAfterLast('/'),
                    subtitle = if (internal) "系统资料 · 不建议手动修改" else null,
                    onClick = { onDirectory(entry.path) },
                )
            } else {
                val file = LocalWorkspaceFile(entry.path, entry.bytes, entry.modifiedAt)
                LocalFileRow(file, showPath = false) { scope.launch { onOpen(file) } }
            }
        }
        if (pageError != null) {
            item(key = "directory-page-read-error") {
                Column {
                    Text("部分文件暂未显示：$pageError",
                        modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
                        style = DsType.caption11.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.warningInk)
                    SyButton(text = stringResource(R.string.common_retry), onClick = onRetry,
                        size = SyButtonSize.Small, variant = SyButtonVariant.Outline)
                }
            }
        }
    }
}

@Composable
private fun LocalFilesEmpty(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = SyMetric.gapPage),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_ui_project_empty),
            contentDescription = null,
            colorFilter = ColorFilter.tint(com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary.copy(alpha = 0.72f)),
            modifier = Modifier.size(100.dp),
        )
        Text(
            title,
            style = DsType.base16Strong.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = SyMetric.gapMd),
        )
        Text(
            body,
            style = DsType.small13.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = SyMetric.gapSm),
        )
    }
}

@Composable
private fun ConversationLocalFileList(
    files: LocalConversationFiles,
    onOpen: suspend (LocalWorkspaceFile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize()) {
        if (files.artifacts.isNotEmpty()) {
            item(key = "local-artifacts-header") {
                Text(
                    stringResource(R.string.panel_artifacts),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                    modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
                )
            }
            items(files.artifacts, key = { "artifact:" + it.path }) { file ->
                LocalFileRow(file) { scope.launch { onOpen(file) } }
            }
        }
        if (files.involved.isNotEmpty()) {
            item(key = "local-involved-header") {
                Text(
                    stringResource(R.string.app_project_context),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                    modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
                )
            }
            items(files.involved, key = { "involved:" + it.path }) { file ->
                LocalFileRow(file) { scope.launch { onOpen(file) } }
            }
        }
    }
}

@Composable
private fun LocalFlatFileList(
    files: List<LocalWorkspaceFile>,
    searchKey: String,
    hasMore: Boolean,
    loadingMore: Boolean,
    pageError: String?,
    onPrefetch: () -> Unit,
    onRetry: () -> Unit,
    onOpen: suspend (LocalWorkspaceFile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = remember(searchKey) { LazyListState() }
    LaunchedEffect(searchKey, files.size, hasMore, loadingMore, pageError) {
        if (hasMore && !loadingMore && pageError == null) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .first { it >= files.size - 40 }
            onPrefetch()
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        if (files.isEmpty() && hasMore) {
            item(key = "search-discovery-in-progress") {
                Text("正在查找文件名…",
                    modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
                    style = DsType.caption11.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
            }
        }
        items(files, key = LocalWorkspaceFile::path) { file ->
            LocalFileRow(file) { scope.launch { onOpen(file) } }
        }
        if (pageError != null) {
            item(key = "search-page-read-error") {
                Column {
                    Text("部分搜索结果暂未显示：$pageError",
                        modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
                        style = DsType.caption11.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.warningInk)
                    SyButton(text = stringResource(R.string.common_retry), onClick = onRetry,
                        size = SyButtonSize.Small, variant = SyButtonVariant.Outline)
                }
            }
        }
    }
}

@Composable
private fun LocalFileList(
    files: List<LocalWorkspaceFile>,
    directory: String,
    onDirectory: (String) -> Unit,
    onOpen: suspend (LocalWorkspaceFile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val prefix = if (directory.isEmpty()) "" else "$directory/"
    val children = remember(files, directory) {
        files.asSequence().mapNotNull { file ->
            if (!file.path.startsWith(prefix)) return@mapNotNull null
            file.path.removePrefix(prefix).takeIf { it.isNotEmpty() }?.substringBefore('/')
        }.distinct().sorted().toList()
    }
    val filesByPath = remember(files) { files.associateBy(LocalWorkspaceFile::path) }
    var showInternalOnly by remember(directory, files) { mutableStateOf(false) }
    if (directory.isEmpty() && children == listOf(".adsh") && !showInternalOnly) {
        SyEmptyState(
            icon = FeatherIcons.Folder,
            title = stringResource(R.string.local_workspace_internal_only_title),
            body = stringResource(R.string.local_workspace_internal_only_body),
            actionText = stringResource(R.string.local_workspace_internal_only_show),
            onAction = { showInternalOnly = true },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    if (children.isEmpty()) {
        LocalFilesEmpty(
            title = stringResource(R.string.local_empty_workspace_title),
            body = stringResource(R.string.local_empty_workspace_body),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(children, key = { prefix + it }) { name ->
            val path = prefix + name
            val file = filesByPath[path]
            if (file == null) {
                LocalDirectoryRow(
                    name = name,
                    onClick = { onDirectory(path) },
                )
            } else {
                LocalFileRow(file) { scope.launch { onOpen(file) } }
            }
        }
    }
}

@Composable
private fun LocalDirectoryRow(
    name: String,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val dark = colors.canvas.luminance() < 0.5f
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SyMetric.gapLg),
        shape = DsShapes.row,
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(horizontal = SyMetric.gapMd, vertical = SyMetric.gapSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
        ) {
            Image(
                painter = painterResource(
                    if (dark) R.drawable.ic_ui_filefolder_dark
                    else R.drawable.ic_ui_filefolder_light,
                ),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
            ) {
                Text(
                    name,
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle ?: stringResource(R.string.panel_folder),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
            Icon(
                FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LocalFileRow(
    file: LocalWorkspaceFile,
    showPath: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val controls = LocalFileSelectionUi.current
    val canShare = isShareableWorkspacePath(file.path)
    val selecting = canShare && controls?.active == true
    val selected = controls?.selected?.contains(file.path) == true
    val fileName = file.path.substringAfterLast('/')
    val dark = colors.canvas.luminance() < 0.5f
    val fileIconRes = workspaceFileIcon(fileName, dark)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SyMetric.gapLg)
            .combinedClickable(
                onClick = { if (selecting) controls?.toggle?.invoke(file.path) else onClick() },
                onLongClick = if (canShare) ({ controls?.begin?.invoke(file.path) }) else null,
            ),
        shape = DsShapes.row,
        color = if (selected) colors.selected.copy(alpha = .20f)
            else androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 70.dp)
                .padding(horizontal = SyMetric.gapMd, vertical = SyMetric.gapSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
        ) {
            Image(
                painter = painterResource(fileIconRes),
                contentDescription = null,
                modifier = Modifier.size(width = 38.dp, height = 50.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
            ) {
                Text(
                    fileName,
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (showPath) "${file.path} · ${formatBytes(file.bytes)}"
                    else formatBytes(file.bytes),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selecting) {
                SyCheckboxControl(
                    checked = selected,
                    onCheckedChange = { controls?.toggle?.invoke(file.path) },
                )
            } else {
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun LocalFilePreviewBody(
    preview: LocalWorkspaceFilePreview,
    loadingPage: Boolean = false,
    hasPrevious: Boolean = false,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = SyMetric.gapLg),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
    ) {
        Text(
            "${preview.file.path} · ${formatBytes(preview.file.bytes)}",
            style = DsType.caption11.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
        )
        if (preview.text == null) {
            Text(
                stringResource(R.string.local_files_binary_hint),
                modifier = Modifier.padding(vertical = SyMetric.gapLg),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
            )
        } else {
            if (preview.truncated) {
                Text(
                    "文件仍有后续内容。当前起点：" + formatBytes(preview.offset),
                    style = DsType.caption11.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                )
            }
            // Compose must never lay out half a megabyte as one Text paragraph.
            // Virtualized chunks preserve the full bounded preview and selection
            // without a single pathological text-measurement operation.
            val previewChunks = remember(preview.text) { chunkWorkspacePreviewText(preview.text) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(previewChunks.size) { index ->
                    SelectionContainer {
                        Text(previewChunks[index], style = DsType.mdCode)
                    }
                }
                item(key = "preview-paging-actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                        if (hasPrevious) SyButton(
                            text = "上一段", onClick = onPrevious, enabled = !loadingPage,
                            size = SyButtonSize.Small, variant = SyButtonVariant.Outline,
                        )
                        if (preview.nextOffset != null) SyButton(
                            text = "继续阅读", onClick = onNext, enabled = !loadingPage,
                            size = SyButtonSize.Small,
                        )
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

/** Split a bounded UI preview into independently measured Compose text blocks. */
internal fun chunkWorkspacePreviewText(text: String, maxChars: Int = 4_096): List<String> {
    require(maxChars >= 2) { "预览分块长度必须至少两个字符" }
    if (text.isEmpty()) return emptyList()
    return buildList {
        var offset = 0
        while (offset < text.length) {
            var end = minOf(text.length, offset + maxChars)
            if (end < text.length && text[end - 1].isHighSurrogate() &&
                text[end].isLowSurrogate()) {
                end--
            }
            add(text.substring(offset, end))
            offset = end
        }
    }
}
