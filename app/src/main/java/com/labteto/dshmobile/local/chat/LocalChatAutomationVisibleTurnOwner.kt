package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalForegroundTurnWakeCoordinator
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalAgentRunHandle
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

/**
 * Claim/release share the foreground lock with Session transitions and Chat admission.
 * DETACHED leaves the automation in its existing session-scoped execution path.
 */
internal enum class AutomationVisibleClaim { CLAIMED, BUSY, DETACHED }

internal fun tryClaimAutomationVisibleTurn(
    handle: LocalAgentRunHandle,
    targetSessionId: String,
    currentSessionId: String,
    visibleState: LocalHarnessState,
    transitioning: Boolean,
    automationJob: Job?,
    onClaimed: () -> Unit = {},
): AutomationVisibleClaim = synchronized(handle.lock) {
    if (
        automationJob?.isActive != true ||
        handle.sessionId != targetSessionId ||
        currentSessionId != targetSessionId ||
        visibleState.sessionId != targetSessionId ||
        visibleState.usageMode != LocalUsageMode.CHAT ||
        transitioning ||
        handle.cancellationRequested
    ) return@synchronized AutomationVisibleClaim.DETACHED
    if (visibleState.loading || visibleState.kernel.running || handle.hasLiveJob()) {
        return@synchronized AutomationVisibleClaim.BUSY
    }
    handle.job = automationJob
    try {
        // A transition cannot interleave slot ownership and its visible running projection.
        onClaimed()
    } catch (error: Throwable) {
        if (handle.job === automationJob) handle.job = null
        throw error
    }
    AutomationVisibleClaim.CLAIMED
}

internal suspend fun awaitAutomationVisibleTurn(
    tryClaim: () -> AutomationVisibleClaim,
    onBusy: suspend () -> Unit = { delay(100) },
): Boolean {
    while (true) {
        when (tryClaim()) {
            AutomationVisibleClaim.CLAIMED -> return true
            AutomationVisibleClaim.DETACHED -> return false
            AutomationVisibleClaim.BUSY -> onBusy()
        }
    }
}

/** Caller holds handle.lock, or is inside another synchronized(handle.lock) section. */
private fun isAutomationVisibleTurnOwner(
    handle: LocalAgentRunHandle,
    targetSessionId: String,
    currentSessionId: String,
    visibleState: LocalHarnessState,
    automationJob: Job?,
): Boolean = automationJob != null &&
    handle.job === automationJob &&
    handle.sessionId == targetSessionId &&
    currentSessionId == targetSessionId &&
    visibleState.sessionId == targetSessionId &&
    visibleState.usageMode == LocalUsageMode.CHAT

internal fun releaseAutomationVisibleTurn(
    handle: LocalAgentRunHandle,
    targetSessionId: String,
    currentSessionId: String,
    visibleState: LocalHarnessState,
    automationJob: Job?,
    onReleased: () -> Unit = {},
): Boolean = synchronized(handle.lock) {
    if (!isAutomationVisibleTurnOwner(handle, targetSessionId, currentSessionId, visibleState, automationJob)) {
        return@synchronized false
    }
    try {
        onReleased()
    } finally {
        if (handle.job === automationJob) handle.job = null
    }
    true
}

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
        val handle = runtimeStateStore.foregroundRunHandle
        var claimed = false
        try {
            return withTimeout(60_000L) {
                awaitAutomationVisibleTurn(
                    tryClaim = {
                        tryClaimAutomationVisibleTurn(
                            handle = handle,
                            targetSessionId = targetSessionId,
                            currentSessionId = runtimeStateStore.currentSessionId,
                            visibleState = runtimeStateStore.state.value,
                            transitioning = runtimeStateStore.sessionTransitioning,
                            automationJob = automationJob,
                            onClaimed = {
                                runtimeStateStore.projection.setForegroundRunning(targetSessionId, true)
                                claimed = true
                            },
                        )
                    },
                )
            }
        } catch (error: Throwable) {
            // withTimeout may cancel concurrently with a successful last poll. If the caller
            // never receives visibleTurnOwned=true, the outer Session lease has nothing to release.
            if (claimed) release(targetSessionId, automationJob)
            throw error
        }
    }

    internal fun commit(
        session: LocalHarnessSession,
        reply: LocalModelReply,
        content: String,
        proactiveMessage: LocalHarnessMessage,
        assistantEventSequence: Long,
        automationJob: Job?,
    ): Boolean {
        val handle = runtimeStateStore.foregroundRunHandle
        val before = synchronized(handle.lock) {
            val current = runtimeStateStore.state.value
            if (
                runtimeStateStore.sessionTransitioning ||
                !isAutomationVisibleTurnOwner(
                    handle, session.id, runtimeStateStore.currentSessionId, current, automationJob,
                )
            ) null else current
        } ?: return false
        // Session navigation cancels and joins this Job before rebinding the visible slot.
        // The non-suspending commit is therefore protected by its owning Job and Session lease;
        // never hold the shared foreground lock during durable EventLog / snapshot disk writes.
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
        return true
    }

    internal fun release(targetSessionId: String, automationJob: Job?) {
        val released = releaseAutomationVisibleTurn(
            handle = runtimeStateStore.foregroundRunHandle,
            targetSessionId = targetSessionId,
            currentSessionId = runtimeStateStore.currentSessionId,
            visibleState = runtimeStateStore.state.value,
            automationJob = automationJob,
            onReleased = {
                runtimeStateStore.foregroundInteractions.cancelAll()
                runtimeStateStore.foregroundInteractions.setDeviceApprovalLease(false)
                runtimeStateStore.projection.setForegroundRunning(targetSessionId, false)
            },
        )
        if (released) wake.startNextIfIdle()?.start()
    }
}
