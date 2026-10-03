package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalGroupChatState

internal data class CharacterBehaviorTuningRestore(
    val persona: PersonaProfile,
    val chatState: ChatCharacterState,
    val changed: Boolean,
)

internal fun resolveCharacterBehaviorTuning(
    current: CharacterBehaviorTuning,
    persona: CharacterBehaviorTuning,
    gallery: CharacterBehaviorTuning? = null,
): CharacterBehaviorTuning {
    var resolved = mergeCharacterBehaviorTuning(current, persona)
    gallery?.let { resolved = mergeCharacterBehaviorTuning(resolved, it) }
    return resolved.normalized()
}

internal fun persistGalleryBehaviorTuning(
    store: ChatPersonaGalleryStore,
    galleryId: String,
    tuning: CharacterBehaviorTuning,
): CharacterBehaviorTuning? {
    val current = store.list().firstOrNull { it.id == galleryId } ?: return null
    val resolved = resolveCharacterBehaviorTuning(current.persona.behaviorTuning, tuning)
    if (resolved == current.persona.behaviorTuning.normalized()) return resolved
    return store.save(
        persona = current.persona.copy(behaviorTuning = resolved),
        sourceSessionId = "",
        history = emptyList(),
        chatState = ChatCharacterState(),
        notes = "",
        existingId = galleryId,
    ).entry.persona.behaviorTuning.normalized()
}

internal fun reconcileCharacterBehaviorTuning(
    personaStore: ChatPersonaStore,
    galleryStore: ChatPersonaGalleryStore,
    personaId: String,
    galleryId: String?,
    chatState: ChatCharacterState,
): CharacterBehaviorTuningRestore {
    val persona = personaStore.get(personaId)
    val galleryTuning = galleryId
        ?.let { id -> galleryStore.list().firstOrNull { it.id == id } }
        ?.persona
        ?.behaviorTuning
    val resolved = resolveCharacterBehaviorTuning(
        chatState.behaviorTuning,
        persona.behaviorTuning,
        galleryTuning,
    )
    galleryId?.let { persistGalleryBehaviorTuning(galleryStore, it, resolved) }
    val restoredPersona = if (persona.behaviorTuning == resolved) persona else {
        personaStore.upsert(persona.copy(behaviorTuning = resolved))
    }
    val restoredState = if (chatState.behaviorTuning == resolved) chatState else {
        chatState.copy(behaviorTuning = resolved)
    }
    return CharacterBehaviorTuningRestore(
        persona = restoredPersona,
        chatState = restoredState,
        changed = restoredPersona !== persona || restoredState != chatState,
    )
}

internal fun reconcileGroupCharacterBehaviorTuning(
    groupChat: LocalGroupChatState,
    personaStore: ChatPersonaStore,
    galleryStore: ChatPersonaGalleryStore,
): LocalGroupChatState {
    if (!groupChat.enabled || groupChat.members.isEmpty()) return groupChat
    val galleryById = galleryStore.list().associateBy(PersonaGalleryEntry::id)
    return groupChat.copy(
        members = groupChat.members.map { member ->
            val stored = personaStore.get(member.personaId)
            val resolved = resolveCharacterBehaviorTuning(
                resolveCharacterBehaviorTuning(
                    member.chatState.behaviorTuning,
                    member.persona.behaviorTuning,
                ),
                stored.behaviorTuning,
                galleryById[member.galleryId]?.persona?.behaviorTuning,
            )
            persistGalleryBehaviorTuning(galleryStore, member.galleryId, resolved)
            val durablePersona = if (stored.behaviorTuning == resolved) stored else {
                personaStore.upsert(stored.copy(behaviorTuning = resolved))
            }
            if (
                member.persona.behaviorTuning == resolved &&
                member.chatState.behaviorTuning == resolved
            ) {
                member
            } else {
                member.copy(
                    persona = member.persona.copy(
                        behaviorTuning = durablePersona.behaviorTuning,
                    ),
                    chatState = member.chatState.copy(behaviorTuning = resolved),
                )
            }
        },
    )
}
