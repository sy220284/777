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
interface PersonaPortraitUiPort {
    suspend fun submit(command: PersonaPortraitUiCommand): UiPersonaPortraitReceipt
}
