package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import androidx.activity.compose.BackHandler

/** Independent thirteen-field editor; an AI suggestion remains an editable draft until Save. */
@Composable
internal fun NextPersonaAuthoringScene(
    owner: PersonaAuthoringUiPort,
    selection: PersonaSelectionUiPort,
    galleryId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var initialized by rememberSaveable(galleryId) { mutableStateOf(false) }
    var revision by rememberSaveable(galleryId) { mutableStateOf(0L) }
    var values by rememberSaveable(galleryId) { mutableStateOf(List(13) { "" }) }
    var description by rememberSaveable(galleryId) { mutableStateOf("") }
    var baseJson by rememberSaveable(galleryId) { mutableStateOf(Json.encodeToString(UiPersonaDraft.serializer(), UiPersonaDraft())) }
    val base = remember(baseJson) { Json.decodeFromString(UiPersonaDraft.serializer(), baseJson) }
    var busy by remember(galleryId) { mutableStateOf(false) }
    var dirty by rememberSaveable(galleryId) { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }
    fun close() { if (dirty) confirmClose = true else onBack() }
    fun reloadLatest() {
        if (dirty) confirmReload = true
        else { initialized = false; editGeneration++; reload++ }
    }
    BackHandler { if (!busy) close() }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false },
        title = { Text(stringResource(R.string.next_persona_discard)) },
        confirmButton = { TextButton(onClick = { onBack() }) { Text(stringResource(R.string.next_persona_discard_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.next_persona_keep_editing)) } })
    if (confirmReload) AlertDialog(onDismissRequest = { confirmReload = false },
        title = { Text("重新读取人物档案？") },
        text = { Text("将舍弃当前未保存的修改，再读取 Owner 中的最新版本。") },
        confirmButton = { TextButton(onClick = {
            confirmReload = false
            dirty = false
            initialized = false
            editGeneration++
            reload++
        }) { Text("舍弃并重新读取") } },
        dismissButton = { TextButton(onClick = { confirmReload = false }) { Text("保留草稿") } })
    var loading by remember(galleryId) { mutableStateOf(galleryId != null) }
    var feedback by remember(galleryId) { mutableStateOf<String?>(null) }
    var editGeneration by remember(galleryId) { mutableStateOf(0L) }
    var reload by remember(galleryId) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.next_persona_save_failed)
    val stale = stringResource(R.string.next_persona_stale)
    fun show(draft: UiPersonaDraft) {
        baseJson = Json.encodeToString(UiPersonaDraft.serializer(), draft)
        values = listOf(draft.name, draft.coreIdentity, draft.franchise) + UI_PERSONA_FACT_FIELDS.map { draft.fields[it].orEmpty() }
    }
    fun draft() = base.copy(name = values[0], coreIdentity = values[1], franchise = values[2],
        fields = UI_PERSONA_FACT_FIELDS.mapIndexed { index, key -> key to values[index + 3] }.toMap())
    LaunchedEffect(owner, galleryId, reload) {
        if (galleryId == null) { initialized = true; loading = false; return@LaunchedEffect }
        loading = true
        try {
            when (val result = owner.read(galleryId)) {
                is UiPersonaAuthoringReceipt.Loaded -> {
                    if (!initialized) {
                        show(result.snapshot.draft)
                        revision = result.snapshot.revision
                        dirty = false
                        initialized = true
                        feedback = null
                    }
                }
                is UiPersonaAuthoringReceipt.Failed -> feedback = result.message ?: failed
                else -> feedback = stale
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { feedback = failure.message ?: failed }
        finally { loading = false }
    }
    val labels = NEXT_PERSONA_FIELD_LABELS
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(if (galleryId == null) R.string.next_persona_create else R.string.next_persona_edit), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { close() }, enabled = !busy) { Text(stringResource(R.string.next_close)) }
            Text(stringResource(R.string.next_persona_archive_hint))
            feedback?.let { Text(it) }
            if (loading) Text(stringResource(R.string.next_loading))
            if (!initialized && !loading) TextButton(onClick = { reload++ }) { Text(stringResource(R.string.next_gallery_refresh)) }
            if (galleryId != null && initialized && !loading) {
                TextButton(onClick = { reloadLatest() }, enabled = !busy) {
                    Text("重新读取最新版")
                }
            }
        }
        items(labels.size) { index ->
            OutlinedTextField(values[index], { text ->
                dirty = true
                editGeneration++
                values = values.toMutableList().also { it[index] = text }
            }, label = { Text(stringResource(labels[index])) },
                modifier = Modifier.fillMaxWidth(), enabled = initialized && !loading && !busy, minLines = if (index > 2) 3 else 1)
        }
        item {
            OutlinedTextField(description, { description = it; editGeneration++ },
                label = { Text(stringResource(R.string.next_persona_ai_description)) }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            TextButton(onClick = {
                val identity = selection.snapshot().identity
                if (identity == null) { feedback = stale; return@TextButton }
                val generation = editGeneration
                val input = draft()
                busy = true
                scope.launch {
                    try {
                        when (val result = owner.suggest(identity, input, description)) {
                            is UiPersonaAuthoringReceipt.Suggested -> if (generation == editGeneration) { show(result.draft); dirty = true }
                            is UiPersonaAuthoringReceipt.Failed -> feedback = result.message ?: failed
                            else -> feedback = stale
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { feedback = failure.message ?: failed }
                    finally { busy = false }
                }
            }, enabled = initialized && !busy && !loading && description.isNotBlank()) {
                Text(stringResource(R.string.next_persona_ai_fill))
            }
            Button(onClick = {
                val input = draft()
                busy = true
                scope.launch {
                    try {
                        when (val result = if (galleryId == null) owner.create(input) else owner.save(galleryId, revision, input)) {
                            is UiPersonaAuthoringReceipt.Saved -> {
                                dirty = false
                                revision = result.revision
                                feedback = null
                                onSaved(result.galleryId)
                            }
                            is UiPersonaAuthoringReceipt.Failed -> feedback = result.message ?: failed
                            else -> feedback = stale
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { feedback = failure.message ?: failed }
                    finally { busy = false }
                }
            }, enabled = initialized && !busy && !loading && values[0].isNotBlank()) { Text(stringResource(R.string.next_persona_save)) }
        }
    }
}

internal val NEXT_PERSONA_FIELD_LABELS = listOf(R.string.next_persona_name, R.string.next_persona_identity, R.string.next_persona_franchise,
    R.string.next_persona_personality, R.string.next_persona_values, R.string.next_persona_narrative,
    R.string.next_persona_beliefs, R.string.next_persona_gap, R.string.next_persona_preferences,
    R.string.next_persona_voice, R.string.next_persona_biography, R.string.next_persona_relationships,
    R.string.next_persona_facts)
