package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.chat.PersonaTransferDocument
import com.labteto.dshmobile.local.chat.PersonaTransferFormat

data class ExportPersonaDocumentCommand(val galleryId: String, val format: PersonaTransferFormat)
class ImportPersonaDocumentCommand(val bytes: ByteArray, val fileName: String?, val mimeType: String?) {
    override fun toString() = "ImportPersonaDocumentCommand(<redacted>)"
}
sealed interface UiPersonaExportReceipt {
    data class Ready(val document: PersonaTransferDocument) : UiPersonaExportReceipt
    data class Failed(val message: String?) : UiPersonaExportReceipt
}
sealed interface UiPersonaImportReceipt {
    data class Imported(val galleryId: String) : UiPersonaImportReceipt
    /** Refresh before retrying; IO failure does not guarantee no partial commit. */
    data class Failed(val message: String?) : UiPersonaImportReceipt
}
interface PersonaTransferUiPort {
    val maxImportBytes: Int
    suspend fun export(command: ExportPersonaDocumentCommand): UiPersonaExportReceipt
    suspend fun import(command: ImportPersonaDocumentCommand): UiPersonaImportReceipt
}
