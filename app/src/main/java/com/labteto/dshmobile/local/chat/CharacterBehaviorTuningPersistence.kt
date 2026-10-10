package com.labteto.dshmobile.local.chat



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

internal data class CharacterBehaviorTuningSave(
    val persona: PersonaProfile,
    val sameBoundCharacter: Boolean,
)

internal fun persistCharacterBehaviorTuning(
    personaStore: ChatPersonaStore,
    galleryStore: ChatPersonaGalleryStore,
    currentPersona: PersonaProfile,
    personaId: String,
    galleryId: String?,
    profile: PersonaProfile,
): CharacterBehaviorTuningSave {
    var saved = personaStore.upsert(profile.copy(id = personaId))
    // An already-bound persona keeps its tuning even when it has no authored
    // V4 identity yet. Automatic name-based gallery merging stays conservative.
    val sameDurableProfile = currentPersona.id == personaId && saved.id == personaId &&
        currentPersona.name.trim() == saved.name.trim()
    val sameBoundCharacter = sameDurableProfile || samePersonaIdentity(currentPersona, saved)
    if (sameBoundCharacter) galleryId?.let { id ->
        persistGalleryBehaviorTuning(galleryStore, id, saved.behaviorTuning)
            ?.takeIf { it != saved.behaviorTuning }
            ?.let { tuning ->
                saved = personaStore.upsert(saved.copy(behaviorTuning = tuning))
            }
    }
    return CharacterBehaviorTuningSave(saved, sameBoundCharacter)
}

internal fun reconcileCharacterBehaviorTuning(
    personaStore: ChatPersonaStore,
    galleryStore: ChatPersonaGalleryStore,
    personaId: String,
    galleryId: String?,
    chatState: ChatCharacterState,
): CharacterBehaviorTuningRestore {
    val galleryPersona = galleryId
        ?.let { id -> galleryStore.list().firstOrNull { it.id == id } }
        ?.persona
    val storedPersona = personaStore.get(personaId).takeIf { it.id == personaId }
    val persona = storedPersona
        ?: galleryPersona?.copy(id = personaId)
        ?: PersonaProfile(id = personaId)
    val galleryTuning = galleryPersona?.behaviorTuning
    val resolved = resolveCharacterBehaviorTuning(
        chatState.behaviorTuning,
        persona.behaviorTuning,
        galleryTuning,
    )
    galleryId?.let { persistGalleryBehaviorTuning(galleryStore, it, resolved) }
    val restoredPersona = if (storedPersona != null && persona.behaviorTuning == resolved) {
        persona
    } else {
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
            val galleryPersona = galleryById[member.galleryId]?.persona
            val storedPersona = personaStore.get(member.personaId).takeIf { it.id == member.personaId }
            val stored = storedPersona
                ?: galleryPersona?.copy(id = member.personaId)
                ?: PersonaProfile(id = member.personaId, name = member.displayName)
            val resolved = resolveCharacterBehaviorTuning(
                member.chatState.behaviorTuning,
                stored.behaviorTuning,
                galleryPersona?.behaviorTuning,
            )
            persistGalleryBehaviorTuning(galleryStore, member.galleryId, resolved)
            if (storedPersona == null || stored.behaviorTuning != resolved) {
                personaStore.upsert(stored.copy(behaviorTuning = resolved))
            }
            if (member.chatState.behaviorTuning == resolved) {
                member
            } else {
                member.copy(chatState = member.chatState.copy(behaviorTuning = resolved))
            }
        },
    )
}
