package com.labteto.dshmobile.ui.screens.local

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalConversationFiles
import com.labteto.dshmobile.local.LocalWorkspaceFile
import com.labteto.dshmobile.local.LocalWorkspaceFilePreview
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
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
    var previewLoading by remember(mode, sessionId) { mutableStateOf(false) }
    var directory by remember(mode, sessionId) { mutableStateOf("") }
    var section by remember(mode, sessionId) {
        mutableStateOf(if (mode == LocalFilesMode.WORKSPACE) 0 else 1)
    }
    val scope = rememberCoroutineScope()
    val readFilesFailed = stringResource(R.string.local_files_read_failed)
    val previewFailed = stringResource(R.string.local_files_preview_failed)

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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: readFilesFailed
        } finally {
            loading = false
        }
    }

    LaunchedEffect(mode, sessionId) { reload() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = DsTheme.colors.bgBase) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = {
                        when {
                            preview != null -> preview = null
                            mode == LocalFilesMode.WORKSPACE && directory.isNotEmpty() ->
                                directory = directory.substringBeforeLast('/', "")
                            else -> onDismiss()
                        }
                    }) {
                        Text(stringResource(if (preview != null || directory.isNotEmpty()) R.string.local_files_back_to_files else R.string.local_files_back))
                    }
                    Text(
                        when {
                            preview != null -> preview?.file?.path.orEmpty()
                            mode == LocalFilesMode.WORKSPACE && section == 0 ->
                                workspacePath + if (directory.isEmpty()) "" else "/$directory"
                            mode == LocalFilesMode.WORKSPACE && section == 1 ->
                                stringResource(R.string.panel_involved_files)
                            mode == LocalFilesMode.WORKSPACE && section == 2 ->
                                stringResource(R.string.panel_artifacts)
                            else -> stringResource(R.string.local_files_conversation_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = DsSpacing.small),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = { scope.launch { reload() } },
                        enabled = !loading && preview == null,
                    ) {
                        Text(stringResource(R.string.local_files_refresh))
                    }
                }

                if (preview == null && mode == LocalFilesMode.WORKSPACE) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium),
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        TextButton(onClick = { section = 0; directory = "" }, enabled = section != 0) {
                            Text(stringResource(R.string.chatlist_workspace_files))
                        }
                        TextButton(onClick = { section = 1; directory = "" }, enabled = section != 1) {
                            Text(stringResource(R.string.panel_involved_files))
                        }
                        TextButton(onClick = { section = 2; directory = "" }, enabled = section != 2) {
                            Text(stringResource(R.string.panel_artifacts))
                        }
                    }
                }

                when {
                    loading -> {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    error != null -> {
                        Text(
                            error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp),
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
                                previewLoading = true
                                error = null
                                try {
                                    preview = loadPreview(file.path)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: previewFailed
                                    preview = null
                                } finally {
                                    previewLoading = false
                                }
                            }
                        } else if (files.isEmpty()) {
                            LocalFilesEmpty(
                                stringResource(
                                    if (section == 2) R.string.panel_artifacts_empty
                                    else R.string.panel_involved_files_empty,
                                ),
                            )
                        } else {
                            LocalFlatFileList(files) { file ->
                                previewLoading = true
                                error = null
                                try {
                                    preview = loadPreview(file.path)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: previewFailed
                                    preview = null
                                } finally {
                                    previewLoading = false
                                }
                            }
                        }
                    }
                    conversation.isEmpty -> LocalFilesEmpty(stringResource(R.string.panel_conversation_files_empty))
                    else -> ConversationLocalFileList(conversation) { file ->
                        previewLoading = true
                        error = null
                        try {
                            preview = loadPreview(file.path)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: previewFailed
                            preview = null
                        } finally {
                            previewLoading = false
                        }
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
}

@Composable
private fun LocalFilesEmpty(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        color = DsTheme.colors.labelTertiary,
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
        LocalFilesEmpty(stringResource(R.string.local_files_workspace_empty))
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
