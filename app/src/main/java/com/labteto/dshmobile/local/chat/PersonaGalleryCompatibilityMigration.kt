package com.labteto.dshmobile.local.chat

internal fun migrateLegacyPersonaGalleryEntry(entry: PersonaGalleryEntry): PersonaGalleryEntry =
    entry.copy(
        groupChatState = entry.groupChatState
            .canonicalizeLegacyCharacterState()
            .withoutLegacyConversationContext(),
        stories = entry.stories.map { story ->
            val legacyState = story.chatState.canonicalizeLegacyCharacterState()
            story.copy(
                chatState = legacyState.withoutLegacyConversationContext(),
                chatContext = story.chatContext.withLegacyFallback(legacyState),
            )
        },
    )
