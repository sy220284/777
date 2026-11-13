package com.labteto.dshmobile.local.chat

/** Record what changed and its source without turning a subjective reading into user truth. */
internal fun observeCharacterStateChanges(
    previous: ChatCharacterState,
    current: ChatCharacterState,
    quotations: Map<String, String>,
    userMessage: String,
    assistantMessage: String,
): ChatCharacterState {
    val oldValues = characterStateObservationValues(previous)
    val changed = characterStateObservationValues(current).filter { (field, value) -> oldValues[field] != value }
    if (changed.isEmpty()) return current.copy(stateObservations = previous.stateObservations)
    val observations = previous.stateObservations.associateBy(ChatStateObservation::field).toMutableMap()
    changed.forEach { (field, value) ->
        val quote = quotations[field]?.trim()?.takeIf { it.isNotBlank() &&
            (userMessage.contains(it) || assistantMessage.contains(it)) }
        observations[field] = ChatStateObservation(
            field = field, value = value,
            previousValue = oldValues[field].orEmpty(),
            basis = (quote ?: listOf(userMessage, assistantMessage)
                .filter(String::isNotBlank).joinToString("\n").takeLast(400)).take(400),
            interpretation = quote == null,
        )
    }
    return current.copy(stateObservations = observations.values.toList())
}

/** Bind observations only to a matching durable turn; a guessed source ID is never stored. */
internal fun ChatCharacterState.bindStateObservationSources(turns: List<ChatPendingTurn>): ChatCharacterState =
    copy(stateObservations = stateObservations.map { observation ->
        if (observation.sourceSequence > 0L || observation.basis.isBlank()) return@map observation
        val source = turns.lastOrNull { turn ->
            turn.userMessage.contains(observation.basis) || turn.assistantMessage.contains(observation.basis) ||
                listOf(turn.userMessage, turn.assistantMessage).filter(String::isNotBlank)
                    .joinToString("\n").endsWith(observation.basis)
        } ?: return@map observation
        observation.copy(sourceSequence = source.sequence,
            sourceUserMessageId = source.userMessageId, sourceAssistantMessageId = source.assistantMessageId)
    })

internal fun characterStateObservationValues(state: ChatCharacterState): Map<String, String> = linkedMapOf(
    "physicalState" to state.physicalState, "mood" to state.mood,
    "relationshipState" to state.relationshipState, "currentFocus" to state.currentFocus,
    "currentUserImpression" to state.currentUserImpression, "activeGoal" to state.activeGoal,
    "currentAgenda" to state.currentAgenda, "internalConflict" to state.internalConflict,
    "immediateConcern" to state.immediateConcern,
    "unresolvedThreads" to state.unresolvedThreads.joinToString("；"),
    "dynamics.unresolvedConflict" to state.dynamics.unresolvedConflict,
    "dynamics.stage" to state.dynamics.stage,
)
