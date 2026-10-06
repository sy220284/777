package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalForegroundTurnWakeCoordinator
import com.labteto.dshmobile.local.model.LocalForegroundModelHistoryRuntime
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalHarnessSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Owns the visible foreground lease used by proactive Chat Automation. */
@Singleton
internal class LocalChatAutomationVisibleTurnOwner @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val chatState: LocalChatStatePort,
    private val chatComposition: LocalChatComposition,
    private val modelHistory: LocalForegroundModelHistoryRuntime,
    private val transcriptRuntime: LocalChatTranscriptRuntime,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val wake: LocalForegroundTurnWakeCoordinator,
) {
    internal suspend fun acquire(targetSessionId: String, automationJob: Job?): Boolean {
        if (runtimeStateStore.state.value.sessionId != targetSessionId) return false
        val handle = runtimeStateStore.foregroundRunHandle
        var ownsVisibleTurn = false
        withTimeout(60_000L) {
            while (!ownsVisibleTurn) {
                ownsVisibleTurn = synchronized(handle.lock) {
                    val busy = runtimeStateStore.sessionTransitioning ||
                        runtimeStateStore.state.value.kernel.running ||
                        handle.job?.isCompleted == false
                    if (!busy) {
                        handle.job = automationJob
                        true
                    } else {
                        false
                    }
                }
                if (!ownsVisibleTurn) delay(100)
            }
        }
        runtimeStateStore.projection.setForegroundRunning(targetSessionId, true)
        return true
    }

    internal fun commit(
        session: LocalHarnessSession,
        reply: LocalModelReply,
        content: String,
        proactiveMessage: LocalHarnessMessage,
        assistantEventSequence: Long,
    ) {
        val before = runtimeStateStore.state.value
        if (before.sessionId != session.id) return
        val eventLog = sessionStorage.eventLogs.get(session.id)
        val nextChatState = chatComposition.turnCoordinator.applyDeterministicInteractionState(
            previous = before.chat.chatState.withoutLegacyConversationContext(),
            userMessage = "",
            assistantMessage = content,
        ).withoutLegacyConversationContext()
        modelHistory.history.append(reply.message)
        modelHistory.refreshMetrics(session.id)
        transcriptRuntime.applyMessages(session.id, listOf(proactiveMessage), assistantEventSequence)
        chatState.update { current ->
            val baseContext = current.chat.chatContext.applySceneTurn(
                userMessage = "",
                assistantMessage = content,
                sequence = assistantEventSequence,
            )
            val pending = ChatPendingTurn(
                sequence = assistantEventSequence,
                assistantMessageId = proactiveMessage.id,
                branchHeadId = proactiveMessage.id,
                userMessage = "",
                assistantMessage = content,
                generation = baseContext.generation,
            )
            val nextContext = baseContext.enqueuePendingDurably(pending, eventLog)
            val nextBranches = if (
                runtimeStateStore.foregroundRunHandle.pendingInputs.size() == 0 &&
                before.transcriptIndex.branchingEligible &&
                before.chat.chatBranches.nodes.isNotEmpty()
            ) {
                appendMaterializedChatBranchMessage(
                    current = current.chat.chatBranches,
                    activeMessages = emptyList(),
                    message = proactiveMessage,
                    parentId = before.transcriptIndex.latestDialogueMessageId,
                    chatState = nextChatState,
                    chatContext = nextContext,
                    replySuggestions = current.chat.replySuggestions,
                )
            } else {
                current.chat.chatBranches
            }
            current.copy(
                chat = current.chat.copy(
                    chatState = nextChatState,
                    chatContext = nextContext,
                    chatBranches = nextBranches,
                ),
            )
        }
        modelHistory.checkpoint(session.id, "chat/proactive-automation")
        sessionStorage.enqueueCurrentSnapshot(session.id)
    }

    internal fun release(targetSessionId: String, automationJob: Job?) {
        runtimeStateStore.foregroundInteractions.cancelAll()
        runtimeStateStore.foregroundInteractions.setDeviceApprovalLease(false)
        runtimeStateStore.projection.setForegroundRunning(targetSessionId, false)
        val handle = runtimeStateStore.foregroundRunHandle
        synchronized(handle.lock) {
            if (handle.job === automationJob) handle.job = null
        }
        wake.startNextIfIdle()?.start()
    }
}
