package com.labteto.dshmobile.uicontract

/** Gallery edits have explicit archive targets; foreground synchronization is separately fenced. */
sealed interface PersonaStoryUiCommand {
    val identity: UiSessionIdentity?
    val galleryId: String
    val storyId: String
    data class SaveDetails(override val identity: UiSessionIdentity?, override val galleryId: String,
        override val storyId: String, val notes: String, val storyStage: String) : PersonaStoryUiCommand
    data class Delete(override val identity: UiSessionIdentity?, override val galleryId: String,
        override val storyId: String) : PersonaStoryUiCommand
}
enum class UiStorySessionSync { APPLIED, NOT_BOUND, DEFERRED }
sealed interface UiPersonaStoryReceipt {
    /** The gallery mutation committed; DEFERRED is not a reason to repeat that mutation. */
    data class Saved(val sessionSync: UiStorySessionSync) : UiPersonaStoryReceipt
    data object Missing : UiPersonaStoryReceipt
    data class Failed(val message: String?) : UiPersonaStoryReceipt
}
interface PersonaStoryUiPort {
    suspend fun submit(command: PersonaStoryUiCommand): UiPersonaStoryReceipt
}

data class DeletePersonaGalleryCommand(val identity: UiSessionIdentity?, val galleryId: String)
sealed interface UiPersonaGalleryDeletionReceipt {
    data class Deleted(val sessionSync: UiStorySessionSync, val portraitCleanupWarning: String? = null) : UiPersonaGalleryDeletionReceipt
    data object Missing : UiPersonaGalleryDeletionReceipt
    data class Failed(val message: String?) : UiPersonaGalleryDeletionReceipt
}
interface PersonaGalleryDeletionUiPort {
    suspend fun delete(command: DeletePersonaGalleryCommand): UiPersonaGalleryDeletionReceipt
}

/** Archive coordination is a Feature API, never a UI dependency on Chat internals. */
sealed interface UiPersonaArchiveMutationReceipt {
    data class Committed(
        val sessionSyncDeferred: Boolean,
        val portraitCleanupWarning: String? = null,
    ) : UiPersonaArchiveMutationReceipt
    data object Missing : UiPersonaArchiveMutationReceipt
    data class Rejected(val reason: String?) : UiPersonaArchiveMutationReceipt
}
interface PersonaArchiveMutationUiPort {
    suspend fun saveStory(command: PersonaStoryUiCommand): UiPersonaArchiveMutationReceipt
    suspend fun deleteGallery(command: DeletePersonaGalleryCommand): UiPersonaArchiveMutationReceipt
}
