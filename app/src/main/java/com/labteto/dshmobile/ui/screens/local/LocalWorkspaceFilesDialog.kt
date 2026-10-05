package com.labteto.dshmobile.ui.screens.local

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.session.LocalConversationFiles
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview
import com.labteto.dshmobile.ui.components.DsPageEmptyState
import com.labteto.dshmobile.ui.components.DsPageLoadingState
import com.labteto.dshmobile.ui.components.DsSegmentedTabs
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class LocalFilesMode { WORKSPACE, CONVERSATION }

@Composable
internal fun LocalWorkspaceFilesDialog(
    mode: LocalFilesMode,
    sessionId: String,
    workspacePath: String,
    loadWorkspace: suspend () -> List<LocalWorkspaceFile>,
    loadConversation: suspend (String) -> LocalConversationFiles,
    loadPreview: suspend (String) -> LocalWorkspaceFilePreview,
    onDismiss: () -> Unit,
) {
    var loading by remember(mode, sessionId) { mutableStateOf(true) }
    var error by remember(mode, sessionId) { mutableStateOf<String?>(null) }
    var workspace by remember(mode, sessionId) { mutableStateOf(emptyList<LocalWorkspaceFile>()) }
    var conversation by remember(mode, sessionId) { mutableStateOf(LocalConversationFiles()) }
    var preview by remember(mode, sessionId) { mutableStateOf<LocalWorkspaceFilePreview?>(null) }
    var previewPath by rememberSaveable(mode, sessionId) { mutableStateOf<String?>(null) }
    var previewLoading by remember(mode, sessionId) { mutableStateOf(false) }
    var directory by rememberSaveable(mode, sessionId) { mutableStateOf("") }
    var section by rememberSaveable(mode, sessionId) {
        mutableStateOf(if (mode == LocalFilesMode.WORKSPACE) 0 else 1)
    }
    val scope = rememberCoroutineScope()
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
        loading = true
        error = null
        preview = null
        try {
            when (mode) {
                LocalFilesMode.WORKSPACE -> {
                    workspace = loadWorkspace()
                    conversation = loadConversation(sessionId)
                }
                LocalFilesMode.CONVERSATION -> conversation = loadConversation(sessionId)
            }
            previewPath?.let { path ->
                preview = loadPreview(path)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: if (previewPath == null) readFilesFailed else previewFailed
            preview = null
            previewPath = null
        } finally {
            loading = false
        }
    }

    LaunchedEffect(mode, sessionId) { reload() }

    fun navigateBack() {
        when {
            preview != null || previewPath != null -> {
                preview = null
                previewPath = null
                error = null
            }
            mode == LocalFilesMode.WORKSPACE && directory.isNotEmpty() ->
                directory = directory.substringBeforeLast('/', "")
            else -> onDismiss()
        }
    }

    val hasInternalBackLayer =
        preview != null ||
            previewPath != null ||
            (mode == LocalFilesMode.WORKSPACE && directory.isNotEmpty())
    BackHandler(enabled = hasInternalBackLayer, onBack = ::navigateBack)

    Surface(Modifier.fillMaxSize(), color = DsTheme.colors.rootSurface()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                val headerTitle = when {
                    preview != null -> preview?.file?.path?.substringAfterLast('/').orEmpty()
                    mode == LocalFilesMode.WORKSPACE -> stringResource(R.string.panel_workspace)
                    else -> stringResource(R.string.local_files_conversation_title)
                }
                val headerSubtitle = when {
                    preview != null -> preview?.file?.path
                    mode == LocalFilesMode.WORKSPACE && section == 0 ->
                        workspacePath + if (directory.isEmpty()) "" else "/$directory"
                    else -> null
                }
                DsTopBar(
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
                    modifier = Modifier.padding(horizontal = DsSpacing.medium),
                    largeTitle = preview == null && directory.isEmpty(),
                    actionIcon = FeatherIcons.RefreshCw,
                    actionContentDescription = stringResource(R.string.local_files_refresh),
                    actionEnabled = !loading && preview == null,
                    onAction = { scope.launch { reload() } },
                )

                if (preview == null && mode == LocalFilesMode.WORKSPACE) {
                    DsSegmentedTabs(
                        labels = listOf(
                            stringResource(R.string.chatlist_workspace_files),
                            stringResource(R.string.panel_involved_files),
                            stringResource(R.string.panel_artifacts),
                        ),
                        selectedIndex = section,
                        onSelect = { selected ->
                            section = selected
                            directory = ""
                        },
                        modifier = Modifier.padding(
                            horizontal = DsSpacing.medium,
                            vertical = DsSpacing.small,
                        ),
                    )
                }

                when {
                    loading -> {
                        DsPageLoadingState(
                            icon = FeatherIcons.Folder,
                            label = stringResource(R.string.local_files_loading),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    error != null -> {
                        DsPageEmptyState(
                            icon = FeatherIcons.AlertTriangle,
                            title = readFilesFailed,
                            body = error.orEmpty(),
                            actionText = stringResource(R.string.common_retry),
                            onAction = { scope.launch { reload() } },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    preview != null -> LocalFilePreviewBody(preview!!)
                    mode == LocalFilesMode.WORKSPACE -> {
                        val files = when (section) {
                            1 -> conversation.involved
                            2 -> conversation.artifacts
                            else -> null
                        }
                        if (files == null) {
                            LocalFileList(workspace, directory, onDirectory = { directory = it }) { file ->
                                openPreview(file.path)
                            }
                        } else if (files.isEmpty()) {
                            LocalFilesEmpty(
                                title = stringResource(
                                    if (section == 2) R.string.local_empty_artifacts_title
                                    else R.string.local_empty_involved_title,
                                ),
                                body = stringResource(
                                    if (section == 2) R.string.local_empty_artifacts_body
                                    else R.string.local_empty_involved_body,
                                ),
                            )
                        } else {
                            LocalFlatFileList(files) { file ->
                                openPreview(file.path)
                            }
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

                if (previewLoading) {
                    Text(
                        stringResource(R.string.local_files_loading),
                        modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                        color = DsTheme.colors.labelTertiary,
                    )
                }
            }
        }
}

@Composable
private fun LocalFilesEmpty(title: String, body: String) {
    DsPageEmptyState(
        icon = FeatherIcons.Folder,
        title = title,
        body = body,
        modifier = Modifier.fillMaxSize(),
    )
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
                    style = MaterialTheme.typography.titleSmall,
                    color = DsTheme.colors.labelSecondary,
                    modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                )
            }
            items(files.artifacts, key = { "artifact:" + it.path }) { file ->
                LocalFileRow(file) { scope.launch { onOpen(file) } }
            }
        }
        if (files.involved.isNotEmpty()) {
            item(key = "local-involved-header") {
                Text(
                    stringResource(R.string.panel_involved_files),
                    style = MaterialTheme.typography.titleSmall,
                    color = DsTheme.colors.labelSecondary,
                    modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
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
    val children = files.asSequence().mapNotNull { file ->
        if (!file.path.startsWith(prefix)) return@mapNotNull null
        file.path.removePrefix(prefix).takeIf { it.isNotEmpty() }?.substringBefore('/')
    }.distinct().sorted().toList()
    val filesByPath = files.associateBy(LocalWorkspaceFile::path)
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
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { Text(stringResource(R.string.panel_folder)) },
                    modifier = Modifier.clickable { onDirectory(path) },
                    colors = ListItemDefaults.colors(
                        containerColor = DsTheme.colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
                    ),
                )
            } else {
                LocalFileRow(file) { scope.launch { onOpen(file) } }
            }
        }
    }
}

@Composable
private fun LocalFileRow(file: LocalWorkspaceFile, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(file.path.substringAfterLast('/')) },
        supportingContent = { Text("${file.path} · ${formatBytes(file.bytes)}") },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = DsTheme.colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
        ),
    )
}

@Composable
private fun LocalFilePreviewBody(preview: LocalWorkspaceFilePreview) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Text(
            "${preview.file.path} · ${formatBytes(preview.file.bytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = DsTheme.colors.labelSecondary,
        )
        if (preview.text == null) {
            Text(
                stringResource(R.string.local_files_binary_hint),
                modifier = Modifier.padding(vertical = DsSpacing.medium),
                color = DsTheme.colors.labelTertiary,
            )
        } else {
            if (preview.truncated) {
                Text(
                    stringResource(R.string.local_files_truncated_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = DsTheme.colors.labelTertiary,
                )
            }
            SelectionContainer {
                Text(
                    preview.text,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
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
