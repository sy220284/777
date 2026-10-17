package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import javax.inject.Inject
import javax.inject.Singleton

/** Chat-owned UI facade. UI consumes domain DTOs/actions without retaining Chat stores/services. */
@Singleton
class LocalChatUiFacade @Inject constructor(
    private val gallery: ChatPersonaGalleryStore,
) {
    internal fun galleryEntries(): List<PersonaGalleryEntry> = gallery.list()

}
