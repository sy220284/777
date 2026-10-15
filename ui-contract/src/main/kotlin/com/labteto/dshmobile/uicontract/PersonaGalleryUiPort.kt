package com.labteto.dshmobile.uicontract

/** Display projection only: Chat continuity, archives and private persona policy stay with Chat. */
data class UiPersonaGalleryStory(
    val id: String,
    val title: String,
    val notes: String,
    val messageCount: Int,
    val storyStage: String = "",
    val availableStages: List<String> = emptyList(),
)

data class UiPersonaGalleryEntry(
    val id: String,
    val name: String,
    val stories: List<UiPersonaGalleryStory>,
)

sealed interface UiPersonaGalleryReadReceipt {
    data class Available(val entries: List<UiPersonaGalleryEntry>) : UiPersonaGalleryReadReceipt
    data class Failed(val message: String?) : UiPersonaGalleryReadReceipt
}

/** Explicit archive identities; these actions do not target the foreground session. */
sealed interface PersonaGalleryUiCommand {
    val galleryId: String
    val storyId: String

    data class RenameStory(
        override val galleryId: String,
        override val storyId: String,
        val title: String,
    ) : PersonaGalleryUiCommand

    data class DeleteHistoryMessage(
        override val galleryId: String,
        override val storyId: String,
        val messageKey: String,
    ) : PersonaGalleryUiCommand
}

data class UiPersonaGalleryHistoryPage(
    val messages: List<UiMessageSnapshot>,
    val totalCount: Int,
    val olderCursor: String?,
)
sealed interface UiPersonaGalleryHistoryReceipt {
    data class Available(val page: UiPersonaGalleryHistoryPage) : UiPersonaGalleryHistoryReceipt
    data object Missing : UiPersonaGalleryHistoryReceipt
    /** The archive changed; reload its latest page rather than replaying an old cursor. */
    data object Stale : UiPersonaGalleryHistoryReceipt
    data class Failed(val message: String?) : UiPersonaGalleryHistoryReceipt
}

interface PersonaGalleryUiPort {
    /** Owner-issued opaque cursor; each response is a bounded page, oldest message first. */
    suspend fun history(galleryId: String, storyId: String, cursor: String? = null): UiPersonaGalleryHistoryReceipt =
        UiPersonaGalleryHistoryReceipt.Failed(null)
    suspend fun read(): UiPersonaGalleryReadReceipt
    suspend fun submit(command: PersonaGalleryUiCommand): UiCommandReceipt
}
