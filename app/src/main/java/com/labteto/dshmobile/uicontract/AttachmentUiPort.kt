package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment

data class ImportUiAttachmentCommand(val identity: UiSessionIdentity, val contentUri: String)
sealed interface UiAttachmentReceipt {
    data class Imported(val attachment: LocalImportedAttachment) : UiAttachmentReceipt
    data class Rejected(val reason: UiCommandRejection) : UiAttachmentReceipt
    data object GrantRevoked : UiAttachmentReceipt
    data class Failed(val message: String?) : UiAttachmentReceipt
}

data class InspectUiAttachmentCommand(val identity: UiSessionIdentity, val attachment: LocalImportedAttachment)

/** A native-player reference to an Owner-verified, app-imported content-addressed file. */
enum class UiAttachmentMediaKind { IMAGE, AUDIO, VIDEO }
data class UiAttachmentMedia(val kind: UiAttachmentMediaKind, val contentUri: String)

/** Only formats the Android native decoder can present. XML/SVG is intentionally a document. */
fun uiAttachmentMediaKind(mimeType: String): UiAttachmentMediaKind? =
    when (mimeType.lowercase().substringBefore(';')) {
        "image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif" ->
            UiAttachmentMediaKind.IMAGE
        "audio/mpeg", "audio/mp3", "audio/mp4", "audio/x-m4a", "audio/wav",
        "audio/x-wav", "audio/ogg", "audio/webm", "audio/flac" ->
            UiAttachmentMediaKind.AUDIO
        "video/mp4", "video/webm", "video/3gpp", "video/quicktime" ->
            UiAttachmentMediaKind.VIDEO
        else -> null
    }
data class UiAttachmentInspection(val displayName: String, val mediaType: String,
    val bytes: Long, val digestVerified: Boolean, val preview: String?, val truncated: Boolean,
    val media: UiAttachmentMedia? = null)
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
