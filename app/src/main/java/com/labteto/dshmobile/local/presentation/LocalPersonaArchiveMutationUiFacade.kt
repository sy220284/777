package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.chat.LocalPersonaArchiveMutationCoordinator
import com.labteto.dshmobile.uicontract.PersonaArchiveMutationUiPort
import com.labteto.dshmobile.uicontract.PersonaGalleryDeletionUiPort
import com.labteto.dshmobile.uicontract.PersonaStoryUiPort

/**
 * Neutral UI composition adapter. Views only see PersonaArchiveMutationUiPort;
 * Chat's mutation coordinator and refresh semantics remain private to Feature.
 */
internal object LocalPersonaArchiveMutationUiFacade {
    fun bind(
        stories: PersonaStoryUiPort,
        gallery: PersonaGalleryDeletionUiPort,
        refresh: suspend () -> Unit,
    ): PersonaArchiveMutationUiPort =
        LocalPersonaArchiveMutationCoordinator(stories, gallery, refresh)
}
