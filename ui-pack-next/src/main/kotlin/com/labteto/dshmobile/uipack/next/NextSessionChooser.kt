package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.session.LocalSessionSummary

/** Projection-only title filtering, including Host overrides, with Owner receipts kept by caller. */
@Composable
internal fun NextSessionChooser(entries: List<LocalSessionSummary>, titles: Map<String, String>, pinned: Set<String>,
    feedback: String?, loading: Boolean, close: () -> Unit, onOpen: (String) -> Unit, onDelete: (String) -> Unit,
    onPin: ((String) -> Unit)?, onRename: ((String) -> Unit)?) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(entries, titles, pinned, query) {
        entries.filter { (titles[it.id] ?: it.title).contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareByDescending<LocalSessionSummary> { it.id in pinned }.thenByDescending { it.updatedAt })
    }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.next_sessions)) },
        text = { Column {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.next_session_search)) })
            feedback?.let { Text(it) }
            if (loading) Text(stringResource(R.string.next_loading))
            else if (filtered.isEmpty()) Text(stringResource(R.string.next_session_search_empty))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(filtered, key = { it.id }) { session ->
                    TextButton(onClick = { onOpen(session.id) }) { Text(titles[session.id] ?: session.title) }
                    TextButton(onClick = { onDelete(session.id) }) { Text(stringResource(R.string.next_delete_session)) }
                    onPin?.let { pin -> TextButton(onClick = { pin(session.id) }) {
                        Text(stringResource(if (session.id in pinned) R.string.next_unpin_session else R.string.next_pin_session))
                    } }
                    onRename?.let { rename -> TextButton(onClick = { rename(session.id) }) {
                        Text(stringResource(R.string.next_rename_session))
                    } }
                }
            }
        } }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.next_close)) } })
}
