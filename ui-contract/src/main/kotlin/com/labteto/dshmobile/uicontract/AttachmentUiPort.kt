package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment

data class ImportUiAttachmentCommand(val identity: UiSessionIdentity, val contentUri: String)
sealed interface UiAttachmentReceipt {
    data class Imported(val attachment: LocalImportedAttachment) : UiAttachmentReceipt
    data class Rejected(val reason: UiCommandRejection) : UiAttachmentReceipt
    data class Failed(val message: String?) : UiAttachmentReceipt
}

/** URI comes from an explicit platform selection. Import never sends or grants a tool capability. */
interface AttachmentUiPort {
    suspend fun import(command: ImportUiAttachmentCommand): UiAttachmentReceipt
}
