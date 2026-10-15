package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview
import com.labteto.dshmobile.uicontract.FilesUiPort
import kotlinx.coroutines.CancellationException

/** The file Owner resolves paths and read budgets; the renderer holds only list/filter/preview. */
@Composable
internal fun NextWorkspaceScene(owner: FilesUiPort, onBack: () -> Unit) {
    var rows by remember(owner) { mutableStateOf(emptyList<LocalWorkspaceFile>()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<LocalWorkspaceFilePreview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    val failed = stringResource(R.string.next_file_read_failed)
    LaunchedEffect(owner, refresh) {
        loading = true
        try { rows = owner.workspaceFiles(); feedback = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: failed }
        finally { loading = false }
    }
    LaunchedEffect(owner, selected, refresh) {
        preview = null
        val path = selected ?: return@LaunchedEffect
        try { preview = owner.previewWorkspaceFile(path) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: failed }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.next_workspace_title), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text(stringResource(R.string.next_close)) }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.next_workspace_search)) })
        TextButton(onClick = { refresh++ }, enabled = !loading) { Text(stringResource(R.string.next_gallery_refresh)) }
        feedback?.let { Text(it) }
        LazyColumn(Modifier.weight(1f)) {
            if (loading) item { Text(stringResource(R.string.next_loading)) }
            items(rows.filter { it.path.contains(query, ignoreCase = true) }, key = { it.path }) { file ->
                TextButton(onClick = { selected = file.path }) { Text(file.path) }
                Text(stringResource(R.string.next_workspace_bytes, file.bytes))
            }
        }
    }
    selected?.let { path -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(path) },
        text = { SelectionContainer { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            val value = preview
            if (value == null) item { Text(feedback ?: stringResource(R.string.next_loading)) }
            else {
                item { Text(value.text ?: stringResource(R.string.next_workspace_binary)) }
                if (value.truncated) item { Text(stringResource(R.string.next_workspace_truncated)) }
            }
        } } }, confirmButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.next_close)) } }) }
}
