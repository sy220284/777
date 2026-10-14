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

/** Native archive summary and title editing; never enters a V6 page or private Chat state. */
@Composable
internal fun NextPersonaGalleryPanel(port: PersonaGalleryUiPort) {
    var open by remember { mutableStateOf(false) }
    if (open) {
        // Leaving the dialog disposes its scope, so late reads cannot reopen or rewrite the editor.
        NextPersonaGalleryDialog(port) { open = false }
    }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.next_gallery_archive)) }
}

@Composable
private fun NextPersonaGalleryDialog(port: PersonaGalleryUiPort, close: () -> Unit) {
    var entries by remember(port) { mutableStateOf<List<UiPersonaGalleryEntry>>(emptyList()) }
    var selected by remember(port) { mutableStateOf<Pair<String, UiPersonaGalleryStory>?>(null) }
    var title by remember(port) { mutableStateOf("") }
    var pending by remember(port) { mutableStateOf(false) }
    var feedback by remember(port) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.next_gallery_unavailable)
    val saved = stringResource(R.string.next_gallery_saved)
    suspend fun read() {
        when (val result = port.read()) {
            is UiPersonaGalleryReadReceipt.Available -> entries = result.entries
            is UiPersonaGalleryReadReceipt.Failed -> feedback = result.message ?: unavailable
        }
    }
    LaunchedEffect(port) { pending = true; try { read() } finally { pending = false } }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.next_gallery_archive)) },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { feedback?.let { Text(it) } }
            entries.forEach { entry ->
                item(key = "entry:${entry.id}") { Text(entry.name) }
                items(entry.stories, key = { "story:${entry.id}:${it.id}" }) { story ->
                    TextButton(onClick = { selected = entry.id to story; title = story.title }, enabled = !pending) {
                        Text(stringResource(R.string.next_gallery_story_summary, story.title, story.messageCount))
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
                }
            }
        } },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.next_gallery_close)) } },
        dismissButton = { TextButton(onClick = {
            if (!pending) { pending = true; scope.launch { try { feedback = null; read() } finally { pending = false } } }
        }, enabled = !pending) { Text(stringResource(R.string.next_gallery_refresh)) } },
    )
}
