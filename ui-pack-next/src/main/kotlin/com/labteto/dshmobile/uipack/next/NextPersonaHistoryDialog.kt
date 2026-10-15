package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One bounded archive page at a time. Message IDs are the Chat Owner's deletion keys. */
@Composable
internal fun NextPersonaHistoryDialog(
    port: PersonaGalleryUiPort, galleryId: String, storyId: String,
    onClose: () -> Unit,
) {
    var cursor by rememberSaveable(galleryId, storyId) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<UiPersonaGalleryHistoryPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var deleting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<UiMessageSnapshot?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.next_gallery_unavailable)
    val stale = stringResource(R.string.next_gallery_history_stale)
    val deleted = stringResource(R.string.next_gallery_history_deleted)
    val missing = stringResource(R.string.next_gallery_history_missing)
    LaunchedEffect(port, galleryId, storyId, cursor, refresh) {
        loading = true; page = null; selected = null
        try {
            when (val receipt = port.history(galleryId, storyId, cursor)) {
                is UiPersonaGalleryHistoryReceipt.Available -> page = receipt.page
                UiPersonaGalleryHistoryReceipt.Stale -> feedback = stale
                UiPersonaGalleryHistoryReceipt.Missing -> feedback = missing
                is UiPersonaGalleryHistoryReceipt.Failed -> feedback = receipt.message ?: failed
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { feedback = error.message ?: failed }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = { if (!deleting) onClose() },
        title = { Text(stringResource(R.string.next_gallery_history)) },
        text = { Column {
            feedback?.let { Text(it) }
            if (loading) Text(stringResource(R.string.next_loading))
            page?.let { data ->
                Text(stringResource(R.string.next_gallery_history_count, data.messages.size, data.totalCount))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(data.messages, key = { it.id }) { message ->
                        Text(stringResource(when (message.role) {
                            UiMessageRole.USER -> R.string.next_gallery_history_user
                            UiMessageRole.ASSISTANT -> R.string.next_gallery_history_persona
                            else -> R.string.next_gallery_history_system
                        }))
                        SelectionContainer { Text(message.text) }
                        TextButton(onClick = { selected = message }, enabled = !loading && !deleting) {
                            Text(stringResource(R.string.next_gallery_history_delete))
                        }
                        HorizontalDivider()
                    }
                }
            }
        } },
        confirmButton = { TextButton(onClick = onClose, enabled = !deleting) {
            Text(stringResource(R.string.next_gallery_close))
        } },
        dismissButton = { Column {
            TextButton(onClick = { feedback = null; cursor = null; refresh++ }, enabled = !loading && !deleting) {
                Text(stringResource(R.string.next_gallery_history_latest))
            }
            page?.olderCursor?.let { older ->
                TextButton(onClick = { feedback = null; cursor = older }, enabled = !loading && !deleting) {
                    Text(stringResource(R.string.next_gallery_history_older))
                }
            }
        } })
    // Process recreation never replays a pending deletion or its confirmation.
    selected?.let { message ->
        AlertDialog(onDismissRequest = { if (!deleting) selected = null },
            title = { Text(stringResource(R.string.next_gallery_history_delete)) },
            text = { Text(stringResource(R.string.next_gallery_history_delete_effect)) },
            confirmButton = { TextButton(enabled = !deleting, onClick = {
                if (deleting) return@TextButton
                deleting = true
                scope.launch {
                    try {
                        when (val result = port.submit(PersonaGalleryUiCommand.DeleteHistoryMessage(galleryId, storyId, message.id))) {
                            UiCommandReceipt.Accepted -> feedback = deleted
                            is UiCommandReceipt.Failed -> feedback = result.message
                            else -> feedback = failed
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { feedback = error.message ?: failed }
                    finally {
                        // Even failed IO may have committed an exclusion. Re-read; never replay.
                        selected = null; deleting = false; cursor = null; refresh++
                    }
                }
            }) { Text(stringResource(R.string.next_gallery_history_delete_confirm)) } },
            dismissButton = { TextButton(onClick = { selected = null }, enabled = !deleting) {
                Text(stringResource(R.string.next_gallery_cancel_delete_story))
            } })
    }
}
