package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.buildDurableChatModelHistory
import com.labteto.dshmobile.local.model.LocalForegroundModelHistoryRuntime
import com.labteto.dshmobile.local.model.chatSystemPrompt
import com.labteto.dshmobile.local.runtime.LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.buildLocalTranscriptRuntimeIndex
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Owns materialized direct-chat branch selection and its durable projection. */
@Singleton
internal class LocalChatBranchCoordinator @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val modelHistoryRuntime: LocalForegroundModelHistoryRuntime,
) {
    internal fun persistCurrentProjection(expectedSessionId: String, reason: String): Boolean {
        val state = runtimeStateStore.state.value
        if (state.sessionId != expectedSessionId) return false
        val eventLog = sessionStorage.eventLogs.get(expectedSessionId)
        eventLog.append(
            "chat/branch-state",
            JsonObject(
                encodeChatBranchStateEvent(state.chat.chatBranches) +
                    ("reason" to JsonPrimitive(reason)),
            ),
        )
        val activeTranscript = activeChatBranchMessages(state.chat.chatBranches)
            .ifEmpty { state.messages }
        val transcriptSequence = com.labteto.dshmobile.local.persistActiveChatTranscript(
            eventLog = eventLog,
            reason = reason,
            activeTranscript = activeTranscript,
        )
        runtimeStateStore.foregroundTranscriptProjectionCursor = maxOf(
            runtimeStateStore.foregroundTranscriptProjectionCursor ?: -1L,
            transcriptSequence,
        )
        return true
    }

    internal fun selectVariant(messageId: String, targetIndex: Int): Boolean {
        val before = runtimeStateStore.state.value
        if (
            before.usageMode != LocalUsageMode.CHAT ||
            before.chat.groupChat.enabled ||
            before.loading ||
            before.kernel.running ||
            runtimeStateStore.foregroundJob?.isCompleted == false ||
            runtimeStateStore.foregroundPendingInputs.size() != 0 ||
            !before.transcriptIndex.branchingEligible
        ) return false

        val lease = LocalSessionRuntimeRegistry.tryAcquire(
            before.sessionId,
            LocalSessionRuntimeKind.MAINTENANCE,
        ) ?: return false
        try {
            val state = runtimeStateStore.state.value
            if (
                state.sessionId != before.sessionId ||
                state.usageMode != LocalUsageMode.CHAT ||
                state.chat.groupChat.enabled ||
                state.loading ||
                state.kernel.running ||
                runtimeStateStore.foregroundJob?.isCompleted == false ||
                runtimeStateStore.foregroundPendingInputs.size() != 0 ||
                !state.transcriptIndex.branchingEligible
            ) return false

            val selected = selectChatBranchVariant(
                state.chat.chatBranches,
                messageId,
                targetIndex,
            ) ?: return false
            val activeMessages = activeChatBranchMessages(selected)
            if (activeMessages.isEmpty() || !chatBranchingEligible(activeMessages)) return false

            val eventLog = sessionStorage.eventLogs.get(state.sessionId)
            val snapshot = chatBranchLastSnapshot(selected)
            val selectedContext = restoreBranchContext(
                snapshot = chatBranchLastContext(selected),
                legacyState = snapshot?.first,
                previousGeneration = state.chat.chatContext.generation,
            ).boundDurablePending(eventLog, "direct")

            if (!modelHistoryRuntime.reset(
                    state.sessionId,
                    buildDurableChatModelHistory(
                        eventLog = eventLog,
                        messages = activeMessages,
                        systemPrompt = chatSystemPrompt(),
                    ),
                )
            ) return false

            runtimeStateStore.projection.update { current ->
                if (current.sessionId != state.sessionId) current else current.copy(
                    messages = activeMessages.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES),
                    transcriptIndex = buildLocalTranscriptRuntimeIndex(activeMessages),
                    chat = current.chat.copy(
                        chatState = (snapshot?.first ?: current.chat.chatState)
                            .withoutLegacyConversationContext(),
                        chatContext = selectedContext,
                        replySuggestions = snapshot?.second.orEmpty(),
                        chatBranches = selected,
                    ),
                    error = null,
                )
            }

            eventLog.append(
                "chat/branch-state",
                JsonObject(
                    encodeChatBranchStateEvent(selected) +
                        ("reason" to JsonPrimitive("variant-selected")),
                ),
            )
            val transcriptSequence = com.labteto.dshmobile.local.persistActiveChatTranscript(
                eventLog = eventLog,
                reason = "variant-selected",
                activeTranscript = activeMessages,
            )
            runtimeStateStore.foregroundTranscriptProjectionCursor = maxOf(
                runtimeStateStore.foregroundTranscriptProjectionCursor ?: -1L,
                transcriptSequence,
            )
            modelHistoryRuntime.checkpoint(state.sessionId, "chat/variant-selected")
            sessionStorage.enqueueCurrentSnapshot(state.sessionId)
            return true
        } finally {
            lease.close()
        }
    }
}
