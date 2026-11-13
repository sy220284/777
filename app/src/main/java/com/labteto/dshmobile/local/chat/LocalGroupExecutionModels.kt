package com.labteto.dshmobile.local.chat



internal data class GroupReplyForStateUpdate(
    val member: LocalGroupChatMember,
    val persona: PersonaProfile,
    val content: String,
)

internal data class GroupStateRefreshBatch(
    val states: Map<String, ChatCharacterState>,
    val complete: Boolean,
    /** Source-backed, member-identified closure proposals for the shared event reconciler. */
    val resolvedContinuityIds: List<String> = emptyList(),
)

internal data class GroupGeneratedReply(
    val member: LocalGroupChatMember,
    val persona: PersonaProfile,
    val content: String = "",
    val failure: Throwable? = null,
    val uiDefinition: String? = null,
    val requestId: String = "",
    val uiUsageReported: Boolean = false,
    val completionReason: com.labteto.dshmobile.local.model.LocalModelCompletionReason = com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED,
    val rawStopReason: String? = null,
)

/**
 * Rehydrate one accountable speaker per pending group event after process/session restore.
 * A current explicit speaker ID wins; legacy records may fall back to their transcript row.
 * Unknown speakers are never assigned to the wrong group member.
 */
internal fun pendingGroupSpeakers(
    pending: List<ChatPendingTurn>,
    members: List<LocalGroupChatMember>,
    transcript: List<com.labteto.dshmobile.local.session.LocalHarnessMessage>,
): List<Pair<LocalGroupChatMember, ChatPendingTurn>>? {
    val recentById = transcript.filter { it.role == "assistant" }.associateBy { it.id }
    val resolved = pending.map { turn ->
        val speakerId = turn.speakerId.ifBlank {
            recentById[turn.assistantMessageId]?.speakerId.orEmpty()
        }
        val member = members.singleOrNull { it.galleryId == speakerId } ?: return null
        member to turn
    }
    // The planner sees ALL pending messages, but each member is updated once per batch
    // from their latest actual utterance. Preserve the chronological order of last turns.
    return resolved.asReversed().distinctBy { it.first.galleryId }.asReversed()
}

/** Both foreground group turns and recovered Pending use the same member-state application. */
internal fun mergeRefreshedGroupMembers(
    members: List<LocalGroupChatMember>,
    refreshed: Map<String, ChatCharacterState>,
    replied: List<GroupReplyForStateUpdate>,
): List<LocalGroupChatMember> = members.map { existing ->
    val state = refreshed[existing.galleryId] ?: return@map existing
    val source = replied.lastOrNull { it.member.galleryId == existing.galleryId }
    existing.copy(
        displayName = source?.persona?.name ?: existing.displayName,
        chatState = state.withoutLegacyConversationContext(),
    )
}

/** Executor delivery must use the accepted repair's identity, completion and accounting facts. */
internal fun groupGeneratedReplyFromFinalReply(
    member: LocalGroupChatMember,
    persona: PersonaProfile,
    reply: com.labteto.dshmobile.local.model.LocalModelReply,
    uiDefinition: String? = null,
): GroupGeneratedReply = GroupGeneratedReply(
    member = member, persona = persona, content = reply.content.orEmpty(),
    uiDefinition = uiDefinition.takeIf { reply.completionReason == com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED },
    requestId = reply.requestId, uiUsageReported = reply.usage.reported,
    completionReason = reply.completionReason, rawStopReason = reply.rawStopReason,
)
