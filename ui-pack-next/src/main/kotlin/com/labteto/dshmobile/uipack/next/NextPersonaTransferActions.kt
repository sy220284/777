package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.local.chat.PersonaTransferFormat
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uihost.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Transfer payloads are opaque. Only Chat decodes/imports archives and diary transactions. */
@Composable
internal fun NextPersonaTransferActions(port: PersonaTransferUiPort, galleryId: String?,
    onChanged: suspend () -> Unit, onFeedback: (String) -> Unit) {
    var pending by remember(port) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.next_gallery_transfer_failed)
    val imported = stringResource(R.string.next_gallery_imported)
    val saved = stringResource(R.string.next_gallery_export_saved)
    val pick = rememberUiDocumentImporter(port.maxImportBytes) { result ->
        when (result) {
            UiDocumentPickReceipt.Reading -> pending = true
            UiDocumentPickReceipt.Cancelled -> pending = false
            is UiDocumentPickReceipt.Failed -> { pending = false; onFeedback(result.message ?: failed) }
            is UiDocumentPickReceipt.Picked -> {
                pending = true
                scope.launch {
                    try {
                        when (val receipt = port.import(ImportPersonaDocumentCommand(result.document.bytes,
                            result.document.name, result.document.mimeType))) {
                            is UiPersonaImportReceipt.Imported -> onFeedback(imported)
                            is UiPersonaImportReceipt.Failed -> onFeedback(receipt.message ?: failed)
                        }
                        onChanged()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { onFeedback(error.message ?: failed); onChanged() }
                    finally { pending = false }
                }
            }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { pending = true; pick() }, enabled = !pending) { Text(stringResource(R.string.next_gallery_import)) }
        PersonaTransferFormat.entries.forEach { format ->
            key(format) {
                val save = rememberUiDocumentSaver(format.mimeType) { result ->
                    pending = false
                    when (result) {
                        UiDocumentWriteReceipt.Saved -> onFeedback(saved)
                        UiDocumentWriteReceipt.Cancelled -> Unit
                        is UiDocumentWriteReceipt.Failed -> onFeedback(result.message ?: failed)
                    }
                }
                TextButton(onClick = {
                    val selectedId = galleryId
                    if (selectedId != null && !pending) {
                        pending = true
                        scope.launch {
                            try {
                                when (val receipt = port.export(ExportPersonaDocumentCommand(selectedId, format))) {
                                    is UiPersonaExportReceipt.Ready -> save(UiExportFile("persona-${selectedId}.${format.extension}", receipt.document.bytes))
                                    is UiPersonaExportReceipt.Failed -> { pending = false; onFeedback(receipt.message ?: failed) }
                                }
                            } catch (cancelled: CancellationException) { pending = false; throw cancelled }
                            catch (error: Exception) { pending = false; onFeedback(error.message ?: failed) }
                        }
                    }
                }, enabled = !pending && galleryId != null) { Text(stringResource(R.string.next_gallery_export_format, format.extension)) }
            }
        }
    }
}
