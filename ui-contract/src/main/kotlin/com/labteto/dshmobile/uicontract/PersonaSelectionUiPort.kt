package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.Flow

data class UiPersonaSelectionSnapshot(
    val identity: UiSessionIdentity?,
    val canSelectForCurrentChat: Boolean,
    val canStartStory: Boolean,
    val unavailableReason: String?,
)

sealed interface PersonaSelectionUiCommand {
    val identity: UiSessionIdentity
    val galleryId: String

    data class SelectForCurrentChat(override val identity: UiSessionIdentity,
        override val galleryId: String) : PersonaSelectionUiCommand
    data class StartStory(override val identity: UiSessionIdentity,
        override val galleryId: String, val storyId: String?, val freshStory: Boolean) : PersonaSelectionUiCommand
}

/** Chat owns character selection and delegates session creation to the existing Session owner. */
interface PersonaSelectionUiPort {
    val snapshots: Flow<UiPersonaSelectionSnapshot>
    fun snapshot(): UiPersonaSelectionSnapshot
    fun submit(command: PersonaSelectionUiCommand): UiCommandReceipt
}
