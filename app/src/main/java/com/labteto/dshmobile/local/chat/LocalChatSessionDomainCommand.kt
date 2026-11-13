package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalSessionDomainCreateSpec
import com.labteto.dshmobile.local.session.LocalSessionDomainModeCommand

internal const val LOCAL_CHAT_SESSION_DOMAIN_ID = "chat"

internal data class LocalChatSessionCreateSpec(
    val galleryEntry: PersonaGalleryEntry? = null,
    val galleryStoryId: String? = null,
    val freshGalleryStory: Boolean = false,
    val chatMode: LocalChatMode? = null,
    val groupEntries: List<PersonaGalleryEntry> = emptyList(),
) : LocalSessionDomainCreateSpec {
    override val domainId: String = LOCAL_CHAT_SESSION_DOMAIN_ID
}

internal data class LocalChatSessionModeCommand(
    val mode: LocalChatMode,
) : LocalSessionDomainModeCommand {
    override val domainId: String = LOCAL_CHAT_SESSION_DOMAIN_ID
}
