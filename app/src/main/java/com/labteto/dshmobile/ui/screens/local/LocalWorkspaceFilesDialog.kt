package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import com.labteto.dshmobile.local.session.LocalConversationFiles
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
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

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
    loadDirectory: suspend (String) -> List<LocalWorkspaceDirectoryEntry> = { emptyList() },
    loadDirectoryPage: suspend (String, Int) -> LocalWorkspaceDirectoryPage = { path, offset ->
        if (offset == 0) LocalWorkspaceDirectoryPage(loadDirectory(path), null)
        else LocalWorkspaceDirectoryPage(emptyList(), null)
    },
    loadSearch: suspend (String) -> List<LocalWorkspaceFile> = { query ->
        loadWorkspace().filter { it.path.contains(query, ignoreCase = true) }
    },
    loadConversation: suspend (String) -> LocalConversationFiles,
    loadPreview: suspend (String) -> LocalWorkspaceFilePreview,
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
    var conversation by remember(mode, sessionId) { mutableStateOf(LocalConversationFiles()) }
    var preview by remember(mode, sessionId) { mutableStateOf<LocalWorkspaceFilePreview?>(null) }
    var previewPath by com.labteto.dshmobile.ui.components.rememberNavigationEntryState<String?>(null, entryRequest, mode, sessionId)
    var loadedForEntry by remember(mode, sessionId, entryRequest) { mutableStateOf(false) }
    var previewLoading by remember(mode, sessionId) { mutableStateOf(false) }
    var directory by com.labteto.dshmobile.ui.components.rememberNavigationEntryState("", entryRequest, mode, sessionId)
    var section by com.labteto.dshmobile.ui.components.rememberNavigationEntryState(
        if (mode == LocalFilesMode.WORKSPACE) -1 else 1, entryRequest, mode, sessionId,
    )
    var query by com.labteto.dshmobile.ui.components.rememberNavigationEntryState("", entryRequest, mode, sessionId)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selecting by rememberSaveable(mode, sessionId, section, directory) { mutableStateOf(false) }
    val selected = remember(mode, sessionId, section, directory) { mutableStateListOf<String>() }
    var sharing by remember { mutableStateOf(false) }
    var shareError by remember(mode, sessionId, section, directory) { mutableStateOf<String?>(null) }
    val chooserTitle = stringResource(R.string.local_files_share_chooser)
    val failedText = stringResource(R.string.local_files_share_failed)
    val limitText = stringResource(R.string.local_files_share_limit, MAX_LOCAL_SHARE_FILES)
    val shareableFiles = remember(mode, section, entries, workspace, conversation, query) {
        val normalizedQuery = query.trim()
        val candidates = when (mode) {
            LocalFilesMode.WORKSPACE -> when {
                normalizedQuery.isNotBlank() ->
                    workspaceSectionFiles(workspace, 1) +
                        workspaceSectionFiles(workspace, 0) +
                        workspaceSectionFiles(workspace, 2)
                section == -1 -> emptyList()
                else -> entries.filterNot(LocalWorkspaceDirectoryEntry::isDirectory).map {
                    LocalWorkspaceFile(it.path, it.bytes, it.modifiedAt)
                }
            }
            LocalFilesMode.CONVERSATION ->
                (conversation.artifacts + conversation.involved).distinctBy { it.path }
        }
        candidates.filter { isShareableWorkspacePath(it.path) &&
            (normalizedQuery.isBlank() || it.path.contains(normalizedQuery, ignoreCase = true)) }
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

    val readFilesFailed = stringResource(R.string.local_files_read_failed)
    val previewFailed = stringResource(R.string.local_files_preview_failed)

    suspend fun openPreview(path: String) {
        previewLoading = true
        error = null
        try {
            preview = loadPreview(path)
            previewPath = path
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: previewFailed
            preview = null
            previewPath = null
        } finally {
            previewLoading = false
        }
    }

    suspend fun reload() {
        val epoch = loadEpoch + 1
        loadEpoch = epoch
        val pageDirectory = directory
        val currentQuery = query.trim()
        val currentSection = section
        selected.clear()
        selecting = false
        loading = true
        loadingNextPage = false
        pageReadError = null
        error = null
        preview = null
        try {
            when (mode) {
                LocalFilesMode.WORKSPACE -> {
                    if (currentQuery.isNotEmpty()) {
                        val found = loadSearch(currentQuery)
                        if (loadEpoch == epoch) workspace = found
                    } else {
                        val page = if (currentSection == -1) LocalWorkspaceDirectoryPage(emptyList(), null)
                            else loadDirectoryPage(pageDirectory.ifBlank { "." }, 0)
                        if (loadEpoch == epoch) {
                            entries = page.entries
                            nextDirectoryOffset = page.nextOffset
                        }
                    }
                }
                LocalFilesMode.CONVERSATION -> {
                    val found = loadConversation(sessionId)
                    if (loadEpoch == epoch) conversation = found
                }
            }
            if (loadEpoch == epoch) {
                lastLoadedSearch = currentQuery
                previewPath?.let { path -> preview = loadPreview(path) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (loadEpoch == epoch) {
                error = failure.message ?: if (previewPath == null) readFilesFailed else previewFailed
                preview = null
                previewPath = null
            }
        } finally {
            if (loadEpoch == epoch) loading = false
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

    // Search is debounced; folder navigation and rendering never wait on a global search.
    LaunchedEffect(mode, sessionId, query) {
        if (mode == LocalFilesMode.WORKSPACE && loadedForEntry && query.trim() != lastLoadedSearch) {
            kotlinx.coroutines.delay(280)
            reload()
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

                if (preview == null) SySearchField(query, { query = it },
                    placeholder = stringResource(R.string.fluid_files_search),
                    modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd))

                if (preview == null && query.isNotBlank() && mode == LocalFilesMode.WORKSPACE) {
                    Text(
                        stringResource(R.string.local_workspace_search_scope),
                        modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapXs),
                        style = DsType.caption11.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                    )
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
                                enabled = selected.isNotEmpty() && !sharing,
                                size = SyButtonSize.Small,
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
                            enabled = !sharing,
                            size = SyButtonSize.Small,
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
                    preview != null -> LocalFilePreviewBody(preview!!)
                    query.isNotBlank() -> {
                        if (shareableFiles.isEmpty()) LocalFilesEmpty(
                            stringResource(R.string.fluid_files_no_matches), query.trim())
                        else LocalFlatFileList(shareableFiles) { file -> openPreview(file.path) }
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
                                scope.launch { reload() }
                            }
                        } else {
                            LocalWorkspaceDirectoryList(
                                entries = if (section == 2 && directory.isEmpty())
                                    entries.filterNot { it.path == "任务" || it.path == "交付成果" }
                                else entries,
                                localizedInternalName = section == 2 && directory.isEmpty(),
                                hasMore = nextDirectoryOffset != null,
                                loadingMore = loadingNextPage,
                                pageError = pageReadError,
                                onPrefetch = ::fetchNextDirectoryPage,
                                section = section,
                                isCategoryRoot = directory.isEmpty() || directory == "任务" || directory == "交付成果",
                                onDirectory = {
                                    directory = it
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
    entries: List<LocalWorkspaceDirectoryEntry>,
    localizedInternalName: Boolean,
    hasMore: Boolean,
    loadingMore: Boolean,
    pageError: String?,
    onPrefetch: () -> Unit,
    section: Int,
    isCategoryRoot: Boolean,
    onDirectory: (String) -> Unit,
    onOpen: suspend (LocalWorkspaceFile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // Prefetch 40 rows ahead; next pages append without replacing the list or a "load more" row.
    LaunchedEffect(entries.size, hasMore, loadingMore, pageError) {
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
                Text("部分文件暂未显示：$"+"pageError",
                    modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
                    style = DsType.caption11.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.warningInk)
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
    onOpen: suspend (LocalWorkspaceFile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize()) {
        items(files, key = LocalWorkspaceFile::path) { file ->
            LocalFileRow(file) { scope.launch { onOpen(file) } }
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
private fun LocalFilePreviewBody(preview: LocalWorkspaceFilePreview) {
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
                    stringResource(R.string.local_files_truncated_hint),
                    style = DsType.caption11.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
                )
            }
            SelectionContainer {
                Text(
                    preview.text,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    style = DsType.mdCode,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}
