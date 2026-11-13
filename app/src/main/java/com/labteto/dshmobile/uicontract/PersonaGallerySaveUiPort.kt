package com.labteto.dshmobile.uicontract

data class SavePersonaGalleryCommand(val identity: UiSessionIdentity, val notes: String,
    val existingId: String? = null, val existingStoryId: String? = null, val forceNewStory: Boolean = false)
sealed interface UiPersonaGallerySaveReceipt {
    data class Saved(val galleryId: String, val storyId: String?, val sessionSync: UiStorySessionSync) : UiPersonaGallerySaveReceipt
    data object Rejected : UiPersonaGallerySaveReceipt
    data class Failed(val message: String?) : UiPersonaGallerySaveReceipt
}
interface PersonaGallerySaveUiPort {
    suspend fun save(command: SavePersonaGalleryCommand): UiPersonaGallerySaveReceipt
}
