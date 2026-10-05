package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalAggregateProjectionPort

/** Results and errors belong to the same frozen character, scene generation and dialogue. */
private fun LocalHarnessState.matchesReplySuggestionTarget(
    before: LocalHarnessState,
    assistantMessageId: String,
): Boolean =
    sessionId == before.sessionId && !loading && !kernel.running &&
        usageMode == LocalUsageMode.CHAT && !chat.groupChat.enabled &&
        transcriptIndex.latestDialogueMessageId == assistantMessageId &&
        chat.personaId == before.chat.personaId &&
        chat.chatPersona == before.chat.chatPersona &&
        chat.galleryId == before.chat.galleryId && chat.galleryStoryId == before.chat.galleryStoryId &&
        chat.chatState == before.chat.chatState &&
        chat.chatContext.generation == before.chat.chatContext.generation

internal fun commitReplySuggestions(
    state: LocalAggregateProjectionPort,
    before: LocalHarnessState,
    assistantMessageId: String,
    suggestions: List<ChatReplySuggestion>,
): Boolean {
    var applied = false
    state.update { current ->
        // update may retry after a failed CAS. Only its final accepted attempt may report success.
        applied = current.matchesReplySuggestionTarget(before, assistantMessageId)
        if (!applied) current else current.copy(
            chat = current.chat.copy(
                replySuggestions = suggestions,
                chatBranches = if (current.transcriptIndex.branchingEligible) {
                    updateChatBranchNodeSnapshot(
                        state = current.chat.chatBranches,
                        messageId = assistantMessageId,
                        chatState = current.chat.chatState,
                        chatContext = current.chat.chatContext,
                        replySuggestions = suggestions,
                    )
                } else current.chat.chatBranches,
            ),
            error = null,
        )
    }
    return applied
}

internal fun commitReplySuggestionError(
    state: LocalAggregateProjectionPort,
    before: LocalHarnessState,
    assistantMessageId: String,
    message: String,
) {
    state.update { current ->
        if (current.matchesReplySuggestionTarget(before, assistantMessageId)) {
            current.copy(error = message)
        } else current
    }
}
