package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalGroupChatMember

/**
 * Single authoritative mapping from gallery entries to runtime group members.
 * Creation and later member edits share this path so persona corrections/state restoration cannot drift.
 */
internal fun resolveLocalGroupChatMembers(
    entries: List<PersonaGalleryEntry>,
    previousMembers: List<LocalGroupChatMember>,
    chatPersonaStore: ChatPersonaStore,
): List<LocalGroupChatMember> =
    entries.distinctBy(PersonaGalleryEntry::id).map { entry ->
        val existingPersona = chatPersonaStore.get(entry.id)
        val saved = chatPersonaStore.upsert(
            entry.persona.copy(
                id = entry.id,
                corrections = (entry.persona.corrections + existingPersona.corrections)
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct(),
            ),
        )
        val previous = previousMembers.firstOrNull { it.galleryId == entry.id }
        LocalGroupChatMember(
            galleryId = entry.id,
            personaId = saved.id,
            displayName = saved.name,
            portraitPath = entry.portraitPath,
            persona = saved,
            chatState = previous?.chatState
                ?: entry.groupChatState.takeIf { it.updatedAt > 0L }
                ?: entry.stories.maxByOrNull { it.updatedAt }?.chatState
                ?: ChatCharacterState(),
        )
    }
