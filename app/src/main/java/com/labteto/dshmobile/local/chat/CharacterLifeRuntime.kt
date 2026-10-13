package com.labteto.dshmobile.local.chat

/** Projects existing ongoing events; habits and the phone clock do not invent a current event. */
internal fun advanceCharacterLife(
    persona: PersonaProfile,
    state: ChatCharacterState,
    now: Long = System.currentTimeMillis(),
    storyTime: String = "",
): CharacterLifeState {
    val events = linkedMapOf<String, CharacterLifeEvent>()
    val projectedSources = setOf("persona", "agenda", "concern", "unfinished")
    state.lifeState.activeEvents.asSequence()
        .filter { it.active && it.source !in projectedSources }
        .forEach { events[it.id] = it }

    fun project(source: String, summary: String, kind: CharacterLifeEventKind) {
        val text = summary.trim().take(220)
        if (text.isBlank()) return
        val id = "$source-${text.hashCode().toUInt().toString(16)}"
        val previous = state.lifeState.activeEvents.firstOrNull { it.id == id }
        events[id] = CharacterLifeEvent(
            id = id,
            summary = text,
            kind = kind,
            source = source,
            startedAt = previous?.startedAt ?: state.updatedAt.takeIf { it > 0L } ?: now,
            updatedAt = state.updatedAt.takeIf { it > 0L } ?: now,
            active = true,
        )
    }
    project("agenda", state.currentAgenda, CharacterLifeEventKind.ONGOING)
    project("concern", state.immediateConcern, CharacterLifeEventKind.BACKGROUND)
    state.unresolvedThreads.takeLast(3).forEach {
        project("unfinished", it, CharacterLifeEventKind.ONGOING)
    }
    return CharacterLifeState(
        lastAdvancedAt = now,
        activeEvents = events.values.toList().takeLast(8),
    )
}

internal fun renderCharacterLifePrompt(life: CharacterLifeState): String {
    // Agenda, concerns and unfinished threads already come from the same current-state
    // source. Rendering them again as "life events" artificially doubles their importance.
    val mirroredSources = setOf("persona", "agenda", "concern", "unfinished")
    val events = life.activeEvents.filter { it.active && it.source !in mirroredSources }.takeLast(3)
    if (events.isEmpty()) return ""
    return buildString {
        appendLine("【已有生活事件】")
        events.forEach { appendLine(it.summary) }
    }.trim()
}

internal fun characterLifeProactiveReason(
    persona: PersonaProfile,
    state: ChatCharacterState,
    now: Long = System.currentTimeMillis(),
): String? = advanceCharacterLife(persona, state, now).activeEvents
    .maxByOrNull(CharacterLifeEvent::updatedAt)?.summary
