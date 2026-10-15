package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.memory.*
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search and paging use the original Memory owner; edits retain the exact selected revision. */
@Composable
internal fun NextMemoryScene(owner: MemoryUiPort, settings: SettingsUiPort, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var readContext by remember { mutableStateOf<com.labteto.dshmobile.local.presentation.LocalSettingsMemoryContext?>(null) }
    var page by remember { mutableStateOf(MemoryManagementPage()) }
    var cursor by remember { mutableStateOf<MemoryManagementCursor?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<MemoryRecord?>(null) }
    var forgetting by remember { mutableStateOf<MemoryRecord?>(null) }
    var mutationBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.next_memory_failed)
    val stale = stringResource(R.string.next_memory_stale)
    val saved = stringResource(R.string.next_memory_saved)
    val forgotten = stringResource(R.string.next_memory_forgotten)
    LaunchedEffect(owner, settings, query, kind, cursor, refresh) {
        loading = true
        val previous = if (cursor == null) emptyList() else page.records
        val context = settings.memoryContext()
        val scopes = when (context.conversationMode) {
            LocalConversationMode.INDEPENDENT -> setOf(MemoryScope.GLOBAL)
            LocalConversationMode.PROJECT -> setOf(MemoryScope.GLOBAL, MemoryScope.PROJECT)
            LocalConversationMode.CONTINUATION -> MemoryScope.entries.toSet()
        }
        try {
            val result = withContext(Dispatchers.IO) { owner.memoryPage(MemoryManagementQuery(
                scopes, context.projectId, context.lineageId, text = query,
                kinds = kind?.let { value -> setOf(MemoryKind.valueOf(value)) } ?: MemoryKind.entries.toSet(), cursor = cursor)) }
            if (settings.memoryContext() != context) { feedback = stale; page = MemoryManagementPage(); return@LaunchedEffect }
            readContext = context
            page = result.copy(records = (previous + result.records).distinctBy { it.id })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: unavailable }
        finally { loading = false }
    }
    fun mutate(command: MemoryUiCommand) {
        if (mutationBusy) return
        if (readContext != settings.memoryContext()) {
            feedback = stale; page = MemoryManagementPage(); cursor = null; refresh++
            return
        }
        mutationBusy = true
        scope.launch {
            try {
                when (val receipt = withContext(Dispatchers.IO) { owner.submit(command) }) {
                    is MemoryUiReceipt.Updated -> { feedback = saved; editing = null }
                    MemoryUiReceipt.Forgotten -> { feedback = forgotten; forgetting = null }
                    is MemoryUiReceipt.CommittedWithWarning -> { feedback = receipt.message; editing = null; forgetting = null }
                    MemoryUiReceipt.StaleOrMissing -> feedback = stale
                    is MemoryUiReceipt.Failed -> feedback = receipt.message ?: unavailable
                }
                cursor = null
                refresh++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: unavailable }
            finally { mutationBusy = false }
        }
    }
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.next_memory_title), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack, enabled = !mutationBusy) { Text(stringResource(R.string.next_close)) }
        OutlinedTextField(query, { query = it; cursor = null; page = MemoryManagementPage() },
            label = { Text(stringResource(R.string.next_memory_search)) }, modifier = Modifier.fillMaxWidth())
        var filterOpen by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { filterOpen = true }) {
                Text(stringResource(kind?.let { memoryKindLabel(MemoryKind.valueOf(it)) } ?: R.string.next_memory_all))
            }
            DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.next_memory_all)) }, onClick = { kind = null; cursor = null; filterOpen = false })
                MemoryKind.entries.forEach { value -> DropdownMenuItem(
                    text = { Text(stringResource(memoryKindLabel(value))) },
                    onClick = { kind = value.name; cursor = null; page = MemoryManagementPage(); filterOpen = false }) }
            }
        }
        Text(stringResource(R.string.next_memory_count, page.records.size, page.totalMatching))
        feedback?.let { Text(it) }
        TextButton(onClick = { cursor = null; refresh++ }, enabled = !loading && !mutationBusy) { Text(stringResource(R.string.next_gallery_refresh)) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(page.records, key = { it.id }) { record ->
                Text(stringResource(memoryKindLabel(record.kind)), style = MaterialTheme.typography.labelMedium)
                Text(record.content)
                Row {
                    TextButton(onClick = { feedback = null; editing = record }, enabled = !mutationBusy) { Text(stringResource(R.string.next_edit)) }
                    TextButton(onClick = { feedback = null; forgetting = record }, enabled = !mutationBusy) { Text(stringResource(R.string.next_memory_forget)) }
                }
                HorizontalDivider()
            }
            if (loading) item { Text(stringResource(R.string.next_loading)) }
            if (page.next != null) item { TextButton(onClick = { if (cursor == page.next) refresh++ else cursor = page.next }, enabled = !loading && !mutationBusy) {
                Text(stringResource(R.string.next_load_older))
            } }
        }
    }
    editing?.let { record ->
        var content by rememberSaveable(record.id, record.updatedAt) { mutableStateOf(record.content) }
        var pinned by rememberSaveable(record.id, record.updatedAt) { mutableStateOf(record.pinned) }
        AlertDialog(onDismissRequest = { if (!mutationBusy) editing = null },
            title = { Text(stringResource(R.string.next_edit)) },
            text = { Column {
                feedback?.let { Text(it) }
                OutlinedTextField(content, { content = it }, enabled = !mutationBusy, minLines = 4)
                Row { Checkbox(pinned, { pinned = it }, enabled = !mutationBusy); Text(stringResource(R.string.next_memory_pin)) }
            } },
            confirmButton = { TextButton(onClick = { mutate(MemoryUiCommand.Update(record, content, pinned)) }, enabled = !mutationBusy) { Text(stringResource(R.string.next_memory_save)) } },
            dismissButton = { TextButton(onClick = { editing = null }, enabled = !mutationBusy) { Text(stringResource(R.string.next_close)) } })
    }
    forgetting?.let { record -> AlertDialog(onDismissRequest = { if (!mutationBusy) forgetting = null },
        title = { Text(stringResource(R.string.next_memory_forget_confirm)) }, text = { Column { Text(record.content); feedback?.let { Text(it) } } },
        confirmButton = { TextButton(onClick = { mutate(MemoryUiCommand.Forget(record)) }, enabled = !mutationBusy) { Text(stringResource(R.string.next_memory_forget)) } },
        dismissButton = { TextButton(onClick = { forgetting = null }, enabled = !mutationBusy) { Text(stringResource(R.string.next_close)) } }) }
}

private fun memoryKindLabel(kind: MemoryKind) = when (kind) {
    MemoryKind.RULE -> R.string.next_memory_rule
    MemoryKind.PREFERENCE -> R.string.next_memory_preference
    MemoryKind.FACT -> R.string.next_memory_fact
    MemoryKind.DECISION -> R.string.next_memory_decision
    MemoryKind.CONSTRAINT -> R.string.next_memory_constraint
    MemoryKind.STATE -> R.string.next_memory_state
    MemoryKind.SUMMARY -> R.string.next_memory_summary
    MemoryKind.RELATIONSHIP_FACT -> R.string.next_memory_relationship_fact
    MemoryKind.RELATIONSHIP_STATE -> R.string.next_memory_relationship_state
    MemoryKind.RELATIONSHIP_PREFERENCE -> R.string.next_memory_relationship_preference
}
