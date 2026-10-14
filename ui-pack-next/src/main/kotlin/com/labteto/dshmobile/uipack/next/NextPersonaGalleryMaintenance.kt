package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uihost.rememberUiAttachmentPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NextPersonaGallerySaveActions(port: PersonaGallerySaveUiPort, selection: UiPersonaSelectionSnapshot?,
    onChanged: suspend () -> Unit, onFeedback: (String) -> Unit) {
    var pending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.next_gallery_archive_saved)
    val deferred = stringResource(R.string.next_gallery_story_saved_sync_pending)
    val unavailable = stringResource(R.string.next_gallery_unavailable)
    fun save(fresh: Boolean) {
        val identity = selection?.identity ?: return
        if (pending) return
        pending = true
        scope.launch {
            try {
                when (val result = port.save(SavePersonaGalleryCommand(identity, "", forceNewStory = fresh))) {
                    is UiPersonaGallerySaveReceipt.Saved -> onFeedback(if (result.sessionSync == UiStorySessionSync.DEFERRED) deferred else saved)
                    UiPersonaGallerySaveReceipt.Rejected -> onFeedback(unavailable)
                    is UiPersonaGallerySaveReceipt.Failed -> onFeedback(result.message ?: unavailable)
                }
                onChanged()
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { pending = false }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { save(false) }, enabled = !pending && selection?.canSaveCurrent == true) { Text(stringResource(R.string.next_gallery_save_current)) }
        TextButton(onClick = { save(true) }, enabled = !pending && selection?.canSaveCurrent == true) { Text(stringResource(R.string.next_gallery_save_new_story)) }
    }
}

@Composable
internal fun NextPersonaPortraitActions(port: PersonaPortraitUiPort, galleryId: String?,
    onChanged: suspend () -> Unit, onFeedback: (String) -> Unit) {
    var pending by remember { mutableStateOf(false) }
    var pickingTarget by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember(galleryId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val updated = stringResource(R.string.next_gallery_portrait_updated)
    val unavailable = stringResource(R.string.next_gallery_unavailable)
    fun submit(command: PersonaPortraitUiCommand) {
        pending = true
        scope.launch {
            try {
                when (val result = port.submit(command)) {
                    is UiPersonaPortraitReceipt.Updated -> onFeedback(result.cleanupWarning ?: updated)
                    UiPersonaPortraitReceipt.Missing -> onFeedback(unavailable)
                    is UiPersonaPortraitReceipt.Failed -> onFeedback(result.message ?: unavailable)
                }
                onChanged()
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { pending = false }
        }
    }
    val pick = rememberUiAttachmentPicker { uri ->
        val captured = pickingTarget
        pickingTarget = null
        if (uri == null) pending = false
        else if (captured == null) { pending = false; onFeedback(unavailable) }
        else submit(PersonaPortraitUiCommand.Import(captured, uri))
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { pickingTarget = galleryId; pending = true; pick() }, enabled = !pending && galleryId != null) { Text(stringResource(R.string.next_gallery_import_portrait)) }
        TextButton(onClick = { confirmRemove = true }, enabled = !pending && galleryId != null) { Text(stringResource(R.string.next_gallery_remove_portrait)) }
        if (confirmRemove && galleryId != null) {
            TextButton(onClick = { confirmRemove = false; submit(PersonaPortraitUiCommand.Remove(galleryId)) }, enabled = !pending) { Text(stringResource(R.string.next_gallery_confirm_remove_portrait)) }
            TextButton(onClick = { confirmRemove = false }, enabled = !pending) { Text(stringResource(R.string.next_gallery_cancel_delete_story)) }
        }
    }
}
