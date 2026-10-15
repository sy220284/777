package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
import com.labteto.dshmobile.local.chat.PersonaAutoFillService
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import javax.inject.Inject
import javax.inject.Singleton

/** Chat-owned UI facade. UI consumes domain DTOs/actions without retaining Chat stores/services. */
@Singleton
class LocalChatUiFacade @Inject constructor(
    private val gallery: ChatPersonaGalleryStore,
    private val autoFill: PersonaAutoFillService,
) {
    internal fun galleryEntries(): List<PersonaGalleryEntry> = gallery.list()

    internal suspend fun autoFillPersona(
        model: String,
        baseUrl: String,
        profileId: String?,
        current: PersonaProfile,
        recentMessages: List<LocalHarnessMessage>,
        description: String,
    ): PersonaProfile = autoFill.generate(model, baseUrl, profileId, current, recentMessages, description)

}
