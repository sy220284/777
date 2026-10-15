package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Local history reads only. Closing disposes the scope and cancels the scan. */
@Composable
internal fun NextMessageSearchDialog(owner: UiMessageHistoryPort, sessionId: String, close: () -> Unit) {
    var query by rememberSaveable(sessionId) { mutableStateOf("") }
    var result by remember { mutableStateOf<UiMessageSearchPage?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.next_search_failed)
    fun search(cursor: UiMessageSearchCursor?) {
        if (busy || query.isBlank()) return
        busy = true; feedback = null; selectedId = null
        val captured = query
        scope.launch {
            try {
                when (val receipt = owner.searchMessages(sessionId, captured, cursor)) {
                    is UiMessageSearchReceipt.Available -> result = receipt.result
                    is UiMessageSearchReceipt.Failed -> { result = null; feedback = receipt.message ?: failed }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { result = null; feedback = error.message ?: failed }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = close,
        title = { Text(stringResource(R.string.next_search_history)) },
        text = { Column {
            OutlinedTextField(query, { query = it; result = null; feedback = null }, enabled = !busy,
                label = { Text(stringResource(R.string.next_search_keyword)) })
            if (busy) Text(stringResource(R.string.next_search_scanning))
            feedback?.let { Text(it) }
            result?.let { data ->
                Text(stringResource(R.string.next_search_count, data.hitIds.size, data.scannedMessages))
                if (data.hitIds.isEmpty()) Text(stringResource(R.string.next_search_no_more))
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(data.page.messages.filter { it.id in data.hitIds }, key = { it.id }) { message ->
                        Text(message.text.take(240))
                        TextButton(onClick = { selectedId = message.id }, enabled = !busy) {
                            Text(stringResource(R.string.next_search_locate))
                        }
                    }
                }
            }
        } },
        confirmButton = { Column {
            TextButton(onClick = { search(null) }, enabled = !busy && query.isNotBlank()) {
                Text(stringResource(R.string.next_search_start))
            }
            result?.nextCursor?.let { cursor ->
                TextButton(onClick = { search(cursor) }, enabled = !busy) {
                    Text(stringResource(R.string.next_search_older))
                }
            }
        } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.next_close)) } })
    // The context window is on top of the search dialog, and never inserts a gap into the live timeline.
    val data = result
    val selected = selectedId
    if (data != null && selected != null) {
        val index = data.page.messages.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        val list = remember(data, selected) { LazyListState(firstVisibleItemIndex = index) }
        AlertDialog(onDismissRequest = { selectedId = null },
            title = { Text(stringResource(R.string.next_search_context)) },
            text = { LazyColumn(Modifier.heightIn(max = 400.dp), state = list) {
                items(data.page.messages, key = { it.id }) { message ->
                    Surface(color = if (message.id == selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface) {
                        Column {
                            Text(stringResource(when (message.role) {
                                UiMessageRole.USER -> R.string.next_search_role_user
                                UiMessageRole.ASSISTANT -> R.string.next_search_role_assistant
                                UiMessageRole.TOOL -> R.string.next_search_role_tool
                                UiMessageRole.SYSTEM -> R.string.next_search_role_system
                            }))
                            SelectionContainer { Text(message.text) }
                        }
                    }
                    HorizontalDivider()
                }
            } },
            confirmButton = { TextButton(onClick = { selectedId = null }) { Text(stringResource(R.string.next_close)) } })
    }
}
