package com.labteto.dshmobile.uicontract

sealed interface PersonaPortraitUiCommand {
    val galleryId: String
    data class Import(override val galleryId: String, val contentUri: String) : PersonaPortraitUiCommand
    data class Remove(override val galleryId: String) : PersonaPortraitUiCommand
}
sealed interface UiPersonaPortraitReceipt {
    data class Updated(val galleryId: String, val cleanupWarning: String? = null) : UiPersonaPortraitReceipt
    data object Missing : UiPersonaPortraitReceipt
    /** Refresh first; a storage failure can follow a committed portrait change. */
    data class Failed(val message: String?) : UiPersonaPortraitReceipt
}
sealed interface UiPersonaPortraitPreviewReceipt {
    /** Private image bytes are transient; never place them in saved UI state or logs. */
    class Ready(val bytes: ByteArray) : UiPersonaPortraitPreviewReceipt {
        override fun toString() = "Ready(<private portrait>)"
    }
    data object Missing : UiPersonaPortraitPreviewReceipt
    data class Failed(val message: String?) : UiPersonaPortraitPreviewReceipt
}
interface PersonaPortraitUiPort {
    suspend fun preview(galleryId: String): UiPersonaPortraitPreviewReceipt = UiPersonaPortraitPreviewReceipt.Failed(null)
    /** Owner-defined maximum size for imported/staged portrait images. */
    val maxImportBytes: Long
    suspend fun submit(command: PersonaPortraitUiCommand): UiPersonaPortraitReceipt
}
