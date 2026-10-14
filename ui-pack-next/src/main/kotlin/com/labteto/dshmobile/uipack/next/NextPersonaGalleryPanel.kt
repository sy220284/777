package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Native archive summary and title editing; never enters a V6 page or private Chat state. */
@Composable
internal fun NextPersonaGalleryPanel(port: PersonaGalleryUiPort, personas: PersonaSelectionUiPort?, transfer: PersonaTransferUiPort?, stories: PersonaStoryUiPort?, deletion: PersonaGalleryDeletionUiPort?) {
    val selection = personas?.snapshots?.collectAsStateWithLifecycle(initialValue = personas.snapshot())?.value
    var open by remember(selection?.identity) { mutableStateOf(false) }
    if (open) {
        // Leaving the dialog disposes its scope, so late reads cannot reopen or rewrite the editor.
        key(selection?.identity) { NextPersonaGalleryDialog(port, personas, selection, transfer, stories, deletion) { open = false } }
    }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.next_gallery_archive)) }
}

@Composable
private fun NextPersonaGalleryDialog(port: PersonaGalleryUiPort, personas: PersonaSelectionUiPort?,
    selection: UiPersonaSelectionSnapshot?, transfer: PersonaTransferUiPort?, stories: PersonaStoryUiPort?, deletion: PersonaGalleryDeletionUiPort?, close: () -> Unit) {
    var entries by remember(port) { mutableStateOf<List<UiPersonaGalleryEntry>>(emptyList()) }
    var selected by remember(port) { mutableStateOf<Pair<String, UiPersonaGalleryStory>?>(null) }
    var exportId by remember(port) { mutableStateOf<String?>(null) }
    var title by remember(port) { mutableStateOf("") }
    var notes by remember(port) { mutableStateOf("") }
    var stage by remember(port) { mutableStateOf("") }
    var confirmDelete by remember(port) { mutableStateOf(false) }
    var deletingEntry by remember(port) { mutableStateOf<String?>(null) }
    var pending by remember(port) { mutableStateOf(false) }
    var feedback by remember(port) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.next_gallery_unavailable)
    val saved = stringResource(R.string.next_gallery_saved)
    val queued = stringResource(R.string.next_gallery_selection_queued)
    val syncPending = stringResource(R.string.next_gallery_story_saved_sync_pending)
    fun submitSelection(command: PersonaSelectionUiCommand) {
        when (val result = personas?.submit(command)) {
            UiCommandReceipt.Accepted -> close()
            UiCommandReceipt.Queued -> feedback = queued
            is UiCommandReceipt.Failed -> feedback = result.message
            else -> feedback = selection?.unavailableReason ?: unavailable
        }
    }
    suspend fun read() {
        when (val result = port.read()) {
            is UiPersonaGalleryReadReceipt.Available -> entries = result.entries
            is UiPersonaGalleryReadReceipt.Failed -> feedback = result.message ?: unavailable
        }
    }
    fun updateStory(command: PersonaStoryUiCommand) {
        if (pending || stories == null) return
        pending = true
        scope.launch {
            try {
                when (val result = stories.submit(command)) {
                    is UiPersonaStoryReceipt.Saved -> {
                        feedback = if (result.sessionSync == UiStorySessionSync.DEFERRED) syncPending else saved
                        selected = null; confirmDelete = false
                    }
                    UiPersonaStoryReceipt.Missing -> { feedback = unavailable; selected = null; confirmDelete = false }
                    is UiPersonaStoryReceipt.Failed -> feedback = result.message ?: unavailable
                }
                read()
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { pending = false }
        }
    }
    LaunchedEffect(port) { pending = true; try { read() } finally { pending = false } }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.next_gallery_archive)) },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { feedback?.let { Text(it) }; selection?.unavailableReason?.let { Text(it) } }
            entries.forEach { entry ->
                item(key = "entry:${entry.id}") {
                    TextButton(onClick = { exportId = entry.id }) { Text(entry.name) }
                    if (deletion != null) {
                        TextButton(onClick = { deletingEntry = entry.id }, enabled = !pending) { Text(stringResource(R.string.next_gallery_delete_character)) }
                        if (deletingEntry == entry.id) {
                            Text(stringResource(R.string.next_gallery_delete_character_effect))
                            TextButton(onClick = {
                                if (!pending) { pending = true; scope.launch {
                                    try {
                                        when (val result = deletion.delete(DeletePersonaGalleryCommand(selection?.identity, entry.id))) {
                                            is UiPersonaGalleryDeletionReceipt.Deleted -> {
                                                feedback = buildList {
                                                    add(if (result.sessionSync == UiStorySessionSync.DEFERRED) syncPending else saved)
                                                    result.portraitCleanupWarning?.let { add(it) }
                                                }.joinToString("\n")
                                                if (selected?.first == entry.id) selected = null
                                                if (exportId == entry.id) exportId = null
                                                deletingEntry = null
                                            }
                                            UiPersonaGalleryDeletionReceipt.Missing -> { feedback = unavailable; deletingEntry = null }
                                            is UiPersonaGalleryDeletionReceipt.Failed -> feedback = result.message ?: unavailable
                                        }
                                        read()
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    finally { pending = false }
                                } }
                            }, enabled = !pending) { Text(stringResource(R.string.next_gallery_confirm_delete_character)) }
                            TextButton(onClick = { deletingEntry = null }, enabled = !pending) { Text(stringResource(R.string.next_gallery_cancel_delete_story)) }
                        }
                    }
                }
                selection?.identity?.let { identity ->
                    item(key = "select:${entry.id}") {
                        TextButton(onClick = { submitSelection(PersonaSelectionUiCommand.SelectForCurrentChat(identity, entry.id)) },
                            enabled = !pending && selection.canSelectForCurrentChat) { Text(stringResource(R.string.next_gallery_use_character)) }
                        TextButton(onClick = { submitSelection(PersonaSelectionUiCommand.StartStory(identity, entry.id, null, true)) },
                            enabled = !pending && selection.canStartStory) { Text(stringResource(R.string.next_gallery_new_story)) }
                    }
                }
                items(entry.stories, key = { "story:${entry.id}:${it.id}" }) { story ->
                    TextButton(onClick = { selected = entry.id to story; title = story.title; notes = story.notes; stage = story.storyStage; confirmDelete = false }, enabled = !pending) {
                        Text(stringResource(R.string.next_gallery_story_summary, story.title, story.messageCount))
                    }
                    selection?.identity?.let { identity ->
                        TextButton(onClick = { submitSelection(PersonaSelectionUiCommand.StartStory(identity, entry.id, story.id, false)) },
                            enabled = !pending && selection.canStartStory) { Text(stringResource(R.string.next_gallery_continue_story)) }
                    }
                }
            }
            selected?.let { (galleryId, story) ->
                item {
                    OutlinedTextField(value = title, onValueChange = { title = it }, enabled = !pending,
                        label = { Text(stringResource(R.string.next_gallery_story_title)) })
                    TextButton(onClick = {
                        if (!pending) {
                            val capturedTitle = title
                            pending = true
                            scope.launch {
                                try {
                                    when (val result = port.submit(PersonaGalleryUiCommand.RenameStory(galleryId, story.id, capturedTitle))) {
                                        UiCommandReceipt.Accepted -> { feedback = saved; selected = null }
                                        is UiCommandReceipt.Failed -> feedback = result.message
                                        else -> feedback = unavailable
                                    }
                                    // Read again after failed IO too: an archive mutation may have committed.
                                    read()
                                } catch (cancelled: CancellationException) { throw cancelled }
                                finally { pending = false }
                            }
                        }
                    }, enabled = !pending && title.isNotBlank()) { Text(stringResource(R.string.next_gallery_save)) }
                    if (stories != null) {
                        OutlinedTextField(value = notes, onValueChange = { notes = it }, enabled = !pending,
                            label = { Text(stringResource(R.string.next_gallery_story_notes)) })
                        OutlinedTextField(value = stage, onValueChange = { stage = it }, enabled = !pending,
                            label = { Text(stringResource(R.string.next_gallery_story_stage)) })
                        story.availableStages.forEach { choice ->
                            TextButton(onClick = { stage = choice }, enabled = !pending) { Text(choice) }
                        }
                        TextButton(onClick = { updateStory(PersonaStoryUiCommand.SaveDetails(selection?.identity, galleryId, story.id, notes, stage)) },
                            enabled = !pending) { Text(stringResource(R.string.next_gallery_save_details)) }
                        TextButton(onClick = { confirmDelete = true }, enabled = !pending) { Text(stringResource(R.string.next_gallery_delete_story)) }
                        if (confirmDelete) {
                            Text(stringResource(R.string.next_gallery_delete_story_effect))
                            TextButton(onClick = { updateStory(PersonaStoryUiCommand.Delete(selection?.identity, galleryId, story.id)) },
                                enabled = !pending) { Text(stringResource(R.string.next_gallery_confirm_delete_story)) }
                            TextButton(onClick = { confirmDelete = false }, enabled = !pending) { Text(stringResource(R.string.next_gallery_cancel_delete_story)) }
                        }
                    }
                }
            }
        } },
        confirmButton = {
            androidx.compose.foundation.layout.Column {
                transfer?.let { owner ->
                    Text(stringResource(R.string.next_gallery_export_target,
                        entries.firstOrNull { it.id == exportId }?.name ?: stringResource(R.string.next_gallery_select_export)))
                    NextPersonaTransferActions(owner, exportId, ::read) { feedback = it }
                }
                TextButton(onClick = close) { Text(stringResource(R.string.next_gallery_close)) }
            }
        },
        dismissButton = { TextButton(onClick = {
            if (!pending) { pending = true; scope.launch { try { feedback = null; read() } finally { pending = false } } }
        }, enabled = !pending) { Text(stringResource(R.string.next_gallery_refresh)) } },
    )
}
