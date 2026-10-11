package com.labteto.dshmobile.local.chat


/** A correction withdraws only derived state supported by that source, leaving raw dialogue intact. */
internal fun reconcileDiaryState(state: ChatCharacterState, entries: List<ChatDiaryEntry>): ChatCharacterState {
    val decisions = entries.filter { !it.active || it.revisions.lastOrNull()?.userCorrected == true }
    if (decisions.isEmpty()) return state
    fun dependsOnDecision(observation: ChatStateObservation): Boolean = decisions.any { entry ->
        entry.sources.any { source ->
            (observation.sourceUserMessageId.isNotBlank() && source.userMessageId == observation.sourceUserMessageId) ||
                (observation.sourceAssistantMessageId.isNotBlank() && source.assistantMessageId == observation.sourceAssistantMessageId)
        }
    }
    val currentValues = characterStateObservationValues(state)
    val invalid = state.stateObservations.filter { observation ->
        observation.interpretation && dependsOnDecision(observation) && currentValues[observation.field] == observation.value
    }.mapTo(hashSetOf(), ChatStateObservation::field)
    // Legacy states lack source IDs. Only exact old derived values may be withdrawn.
    val old = decisions.flatMap { entry ->
        val revisions = ChatDiaryEntryPolicy.revisionsOf(entry)
        (if (entry.active) revisions.dropLast(1) else revisions).flatMap { revision ->
            listOf(revision.feeling, revision.innerThought, revision.relationshipMeaning, revision.unresolvedEcho)
        }
    }.filter(String::isNotBlank).toSet()
    fun withdrawn(field: String, value: String) = field in invalid ||
        (state.stateObservations.none { it.field == field } && value.isNotBlank() && value in old &&
            decisions.any { state.updatedAt <= it.updatedAt })
    fun withdrawnThread(text: String): Boolean {
        if (text in old) return true
        if ("unresolvedThreads" !in invalid) return false
        val terms = ChatDiaryEntryPolicy.queryTerms(text)
        if (terms.isEmpty()) return false
        return decisions.any { entry ->
            val oldEvents = ChatDiaryEntryPolicy.revisionsOf(entry).let { if (entry.active) it.dropLast(1) else it }
            oldEvents.any { revision ->
                val anchors = ChatDiaryEntryPolicy.queryTerms(revision.event + " " + revision.unresolvedEcho)
                val shared = terms.count(anchors::contains)
                shared >= 2 && shared * 2 >= terms.size
            }
        }
    }
    return state.copy(
        physicalState = state.physicalState.takeUnless { withdrawn("physicalState", it) }.orEmpty(),
        mood = state.mood.takeUnless { withdrawn("mood", it) } ?: "自然",
        relationshipState = state.relationshipState.takeUnless { withdrawn("relationshipState", it) } ?: "熟悉中",
        currentFocus = state.currentFocus.takeUnless { withdrawn("currentFocus", it) }.orEmpty(),
        currentUserImpression = state.currentUserImpression.takeUnless { withdrawn("currentUserImpression", it) }.orEmpty(),
        activeGoal = state.activeGoal.takeUnless { withdrawn("activeGoal", it) }.orEmpty(),
        currentAgenda = state.currentAgenda.takeUnless { withdrawn("currentAgenda", it) }.orEmpty(),
        internalConflict = state.internalConflict.takeUnless { withdrawn("internalConflict", it) }.orEmpty(),
        immediateConcern = state.immediateConcern.takeUnless { withdrawn("immediateConcern", it) }.orEmpty(),
        unresolvedThreads = state.unresolvedThreads.filterNot(::withdrawnThread),
        dynamics = state.dynamics.copy(
            unresolvedConflict = state.dynamics.unresolvedConflict.takeUnless { withdrawn("dynamics.unresolvedConflict", it) }.orEmpty(),
            sharedMoments = state.dynamics.sharedMoments.filterNot(old::contains),
            sharedObjects = state.dynamics.sharedObjects.filterNot(old::contains),
        ),
        stateObservations = state.stateObservations.filterNot { it.field in invalid },
    )
}

internal fun reconcileDiaryContext(context: ChatContextState, entries: List<ChatDiaryEntry>): ChatContextState {
    val decisions = entries.filter { !it.active || it.revisions.lastOrNull()?.userCorrected == true }
    if (decisions.isEmpty()) return context
    val sourceIds = decisions.flatMap { it.sources }.flatMap { listOf(it.userMessageId, it.assistantMessageId) }
        .filter(String::isNotBlank).toSet()
    val obsolete = decisions.flatMap { entry ->
        val revisions = ChatDiaryEntryPolicy.revisionsOf(entry)
        (if (entry.active) revisions.dropLast(1) else revisions).flatMap { listOf(it.event, it.unresolvedEcho) }
    }.filter(String::isNotBlank).toSet()
    val dependent = context.continuity.evidence.filter {
        it.sourceUserMessageId in sourceIds || it.sourceAssistantMessageId in sourceIds
    }.mapTo(hashSetOf()) { it.text } + obsolete
    val continuity = context.continuity
    return context.copy(continuity = continuity.copy(
        recentEvents = continuity.recentEvents.filterNot(dependent::contains),
        decisions = continuity.decisions.filterNot(dependent::contains),
        unfinished = continuity.unfinished.filterNot(dependent::contains),
        evidence = continuity.evidence.filterNot { it.text in dependent },
    ))
}

internal fun renderDiaryDecisions(entries: List<ChatDiaryEntry>): String =
    packCompleteCharacterUnits(entries.filter { it.active && it.revisions.lastOrNull()?.userCorrected == true }
        .sortedByDescending(ChatDiaryEntry::updatedAt).map { "用户已纠正的经历：${it.event}" }, 180).joinToString("\n")
