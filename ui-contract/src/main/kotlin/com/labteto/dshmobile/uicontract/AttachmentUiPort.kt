package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment

data class ImportUiAttachmentCommand(val identity: UiSessionIdentity, val contentUri: String)
sealed interface UiAttachmentReceipt {
    data class Imported(val attachment: LocalImportedAttachment) : UiAttachmentReceipt
    data class Rejected(val reason: UiCommandRejection) : UiAttachmentReceipt
    data class Failed(val message: String?) : UiAttachmentReceipt
}

data class InspectUiAttachmentCommand(val identity: UiSessionIdentity, val attachment: LocalImportedAttachment)
data class UiAttachmentInspection(val displayName: String, val mediaType: String,
    val bytes: Long, val digestVerified: Boolean, val preview: String?, val truncated: Boolean)
sealed interface UiAttachmentInspectionReceipt {
    data class Ready(val value: UiAttachmentInspection) : UiAttachmentInspectionReceipt
    data class Unavailable(val message: String) : UiAttachmentInspectionReceipt
    data class Rejected(val reason: UiCommandRejection) : UiAttachmentInspectionReceipt
}
/** URI comes from an explicit platform selection. Import never sends or grants a tool capability. */
interface AttachmentUiPort {
    suspend fun import(command: ImportUiAttachmentCommand): UiAttachmentReceipt
    suspend fun inspect(command: InspectUiAttachmentCommand): UiAttachmentInspectionReceipt =
        UiAttachmentInspectionReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
}
