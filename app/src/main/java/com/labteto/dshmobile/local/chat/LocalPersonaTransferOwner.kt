package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.uicontract.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Chat-owned transfer transaction. Uses the existing gallery and diary writers and recovery. */
@Singleton
class LocalPersonaTransferOwner @Inject constructor(
    private val chat: LocalChatRuntime,
    private val gallery: ChatPersonaGalleryStore,
) : PersonaTransferUiPort {
    override val maxImportBytes: Int get() = MAX_PERSONA_TRANSFER_BYTES

    override suspend fun export(command: ExportPersonaDocumentCommand): UiPersonaExportReceipt = withContext(Dispatchers.IO) {
        try {
            val diary = chat.diaryEntriesForTransfer("gallery:${command.galleryId}")
            UiPersonaExportReceipt.Ready(gallery.exportPersonaDocument(command.galleryId, command.format, diary))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiPersonaExportReceipt.Failed(failure.message) }
    }

    override suspend fun import(command: ImportPersonaDocumentCommand): UiPersonaImportReceipt = withContext(Dispatchers.IO) {
        try {
            val entry = when (val prepared = gallery.preparePersonaDocumentImport(command.bytes, command.fileName, command.mimeType)) {
                is PersonaGalleryPreparedImport.Share -> gallery.commitPersonaDocumentImport(prepared)
                is PersonaGalleryPreparedImport.Archive -> chat.importDiaryEntriesForTransfer(
                    "gallery:${prepared.entry.id}", prepared.entry.persona.name, prepared.diaryEntries,
                ) { gallery.commitPersonaDocumentImport(prepared) }
            }
            UiPersonaImportReceipt.Imported(entry.id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiPersonaImportReceipt.Failed(failure.message) }
    }
}
