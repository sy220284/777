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

@Singleton
internal class LocalChatBranchCoordinator @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val chatState: LocalChatStatePort,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val modelHistoryRuntime: LocalForegroundModelHistoryRuntime,
) {
    internal fun persistCurrentProjection(expectedSessionId: String, reason: String): Boolean {
        val aggregate = runtimeStateStore.state.value
        val state = chatState.value
        if (state.sessionId != expectedSessionId || aggregate.sessionId != expectedSessionId) return false
        val eventLog = sessionStorage.eventLogs.get(expectedSessionId)
        val activeTranscript = activeChatBranchMessages(state.chat.chatBranches)
            .ifEmpty { state.messages }
        val event = appendChatProjectionCommit(
            eventLog = eventLog,
            reason = reason,
            activeTranscript = activeTranscript,
            modelHistory = modelHistoryRuntime.history.snapshot(),
            state = LocalTimelineRewriteState(
                chatState = state.chat.chatState,
                chatContext = state.chat.chatContext,
                chatBranches = state.chat.chatBranches,
                groupChat = state.chat.groupChat,
                replySuggestions = state.chat.replySuggestions,
            ),
        )
        runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor = maxOf(
            runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor ?: -1L,
            event.sequence,
        )
        return true
    }

    internal fun selectVariant(messageId: String, targetIndex: Int): Boolean {
        val before = chatState.value
        if (
            before.usageMode != LocalUsageMode.CHAT ||
            before.chat.groupChat.enabled ||
            before.loading ||
            before.kernel.running ||
            runtimeStateStore.foregroundRunHandle.hasLiveJob() ||
            runtimeStateStore.foregroundRunHandle.pendingInputs.size() != 0 ||
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
                runtimeStateStore.foregroundRunHandle.hasLiveJob() ||
                runtimeStateStore.foregroundRunHandle.pendingInputs.size() != 0 ||
                !state.transcriptIndex.branchingEligible
            ) return false

            val selected = selectChatBranchVariant(state.chat.chatBranches, messageId, targetIndex)
                ?: return false
            val activeMessages = activeChatBranchMessages(selected)
            if (activeMessages.isEmpty() || !chatBranchingEligible(activeMessages)) return false

            val eventLog = sessionStorage.eventLogs.get(state.sessionId)
            val snapshot = chatBranchLastSnapshot(selected)
            val selectedContext = restoreBranchContext(
                snapshot = chatBranchLastContext(selected),
                legacyState = snapshot?.first,
                previousGeneration = state.chat.chatContext.generation,
            ).boundDurablePending(eventLog, "direct")
            val selectedChatState = (snapshot?.first ?: state.chat.chatState)
                .withoutLegacyConversationContext()
            val selectedSuggestions = snapshot?.second.orEmpty()
            val selectedHistory = buildDurableChatModelHistory(
                eventLog = eventLog,
                messages = activeMessages,
                systemPrompt = chatSystemPrompt(),
            )
            val event = appendChatProjectionCommit(
                eventLog = eventLog,
                reason = "variant-selected",
                activeTranscript = activeMessages,
                modelHistory = selectedHistory,
                state = LocalTimelineRewriteState(
                    chatState = selectedChatState,
                    chatContext = selectedContext,
                    chatBranches = selected,
                    groupChat = state.chat.groupChat,
                    replySuggestions = selectedSuggestions,
                ),
            )

            if (!modelHistoryRuntime.reset(state.sessionId, selectedHistory)) return true
            chatState.update { current ->
                if (current.sessionId != state.sessionId) current else current.copy(
                    messages = activeMessages.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES),
                    transcriptIndex = buildLocalTranscriptRuntimeIndex(activeMessages),
                    chat = current.chat.copy(
                        chatState = selectedChatState,
                        chatContext = selectedContext,
                        replySuggestions = selectedSuggestions,
                        chatBranches = selected,
                    ),
                    error = null,
                )
            }
            runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor = maxOf(
                runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor ?: -1L,
                event.sequence,
            )
            runCatching {
                modelHistoryRuntime.checkpoint(state.sessionId, "chat/variant-selected")
            }.onFailure { error ->
                chatState.update { current ->
                    if (current.sessionId == state.sessionId) {
                        current.copy(
                            error = "分支已切换，模型历史检查点写入失败：${error.message ?: error::class.java.simpleName}",
                        )
                    } else current
                }
            }
            sessionStorage.enqueueCurrentSnapshot(state.sessionId)
            return true
        } finally {
            lease.close()
        }
    }
}
