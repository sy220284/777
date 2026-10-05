package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.interaction.LocalInteractionCoordinator
import com.labteto.dshmobile.local.interaction.LocalInteractionStatePort
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.runtime.LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES
import com.labteto.dshmobile.local.runtime.LocalAgentRunHandle
import com.labteto.dshmobile.local.runtime.projectExecutionJobs
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalTranscriptRuntime
import com.labteto.dshmobile.local.session.LocalTranscriptStatePort
import com.labteto.dshmobile.local.session.appendLocalTranscriptRuntimeIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * One Work turn's session-bound mutable runtime.
 *
 * Work owns its domain state and Work-specific policy; shared run facts live in LocalAgentRunHandle. The persisted Session envelope is an immutable base used
 * when materializing a durable snapshot; Chat and other product domains never become mutable Work
 * state just because the run continues while its conversation is off screen.
 */
internal class LocalWorkRunBinding(
    val sessionId: String,
    initialState: LocalWorkRunState,
    private val sessionBase: LocalHarnessSession,
    val runHandle: LocalAgentRunHandle,
    val eventLog: LocalSessionEventLog,
    pruneToolResult: (String) -> String,
) {
    init {
        require(initialState.sessionId == sessionId) { "Work run 状态与会话编号不一致" }
        require(sessionBase.id == sessionId) { "Work run 持久化基线与会话编号不一致" }
        require(runHandle.sessionId == sessionId) { "Work run 运行句柄与会话编号不一致" }
    }

    val state = MutableStateFlow(initialState)
    val workState: LocalWorkStatePort = localWorkRunStatePort(state)
    val enabledOptionalTools = linkedSetOf<String>()
    /** Main Agent and every child spawned by this Work run share one admission budget and breaker. */
    val executionControl = LocalWorkExecutionControl()

    private val interactionStatePort = object : LocalInteractionStatePort {
        override fun pendingApproval(): LocalApproval? = state.value.work.pendingApproval

        override fun setPendingApproval(approval: LocalApproval?) {
            state.update { current ->
                current.copy(work = current.work.copy(pendingApproval = approval))
            }
        }

        override fun clearPendingApproval(callId: String) {
            state.update { current ->
                if (current.work.pendingApproval?.callId == callId) {
                    current.copy(work = current.work.copy(pendingApproval = null))
                } else current
            }
        }

        override fun setPendingQuestion(question: LocalQuestion?) {
            state.update { current ->
                current.copy(work = current.work.copy(pendingQuestion = question))
            }
        }

        override fun clearPendingQuestion(callId: String) {
            state.update { current ->
                if (current.work.pendingQuestion?.callId == callId) {
                    current.copy(work = current.work.copy(pendingQuestion = null))
                } else current
            }
        }

        override fun clearPendingInteractions() {
            state.update { current ->
                current.copy(
                    work = current.work.copy(
                        pendingApproval = null,
                        pendingQuestion = null,
                    ),
                )
            }
        }

        override fun deviceApprovalLeaseEnabled(): Boolean = state.value.work.deviceApprovalLease

        override fun setDeviceApprovalLease(enabled: Boolean) {
            state.update { current ->
                current.copy(work = current.work.copy(deviceApprovalLease = enabled))
            }
        }
    }

    val interactions = LocalInteractionCoordinator(interactionStatePort)

    private val transcriptStatePort = object : LocalTranscriptStatePort {
        override fun appendMessages(messages: List<LocalHarnessMessage>, runtimeWindowMessages: Int) {
            state.update { current ->
                current.copy(
                    messages = (current.messages + messages).takeLast(runtimeWindowMessages),
                    transcriptIndex = appendLocalTranscriptRuntimeIndex(current.transcriptIndex, messages),
                )
            }
        }
    }

    private var approvalProjection: Job? = null

    internal fun observeApprovalMode(preferences: LocalApprovalPreferences) {
        approvalProjection?.cancel()
        approvalProjection = preferences.observeMode { enabled ->
            state.update { it.copy(safeAutoApprovalEnabled = enabled) }
        }
    }

    internal fun stopApprovalProjection() {
        approvalProjection?.cancel()
        approvalProjection = null
    }

    /** Automatic continuation is bounded by the shared Work continuation policy. */
    @Volatile
    var automaticContinuationCount: Int = 0

    @Volatile
    var continuationParentRunId: String? = null

    val transcriptRuntime = LocalTranscriptRuntime(
        state = transcriptStatePort,
        pruneToolResult = pruneToolResult,
        runtimeWindowMessages = LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES,
        onProjected = { sequence ->
            runHandle.transcriptProjectionCursor = maxOf(runHandle.transcriptProjectionCursor ?: -1L, sequence)
        },
    )

    internal fun aggregateSnapshot() = state.value.toAggregateSnapshot()

    /**
     * Materialize a durable Session snapshot without making Session infrastructure depend on Work.
     * The immutable base preserves non-Work domain data while Work contributes only its own mutable
     * fields plus the transcript projection it owns during the run.
     */
    internal fun persistenceSnapshot(): LocalHarnessSession {
        val current = state.value
        return sessionBase.copy(
            title = current.transcriptIndex.firstUserTitle ?: sessionBase.title,
            updatedAt = current.transcriptIndex.latestCreatedAt.takeIf { it > 0L }
                ?: System.currentTimeMillis(),
            usageMode = LocalUsageMode.WORK,
            transcriptWindow = current.messages.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES),
            transcriptIndex = current.transcriptIndex,
            plan = current.work.plan,
            todos = current.work.todos,
            goal = current.work.goal,
            planMode = current.work.planMode,
            controlProjectedThroughSequence = eventLog.latestSequence(),
            transcriptProjectedThroughSequence = runHandle.transcriptProjectionCursor,
        )
    }

    /**
     * Request cancellation of this session-owned Work run without waiting for teardown.
     *
     * The run remains visibly running until its coroutine crosses the real cancellation boundary;
     * publishing idle before the Session owner releases would admit conflicting user actions.
     */
    internal fun requestCancel(): Boolean {
        val activeJob = runHandle.job?.takeIf { it.isCompleted == false } ?: return false
        try {
            clearPendingForCancellation()
        } finally {
            activeJob.cancel()
        }
        return true
    }

    /** Tear down only this session-owned runtime; other Work conversations keep running. */
    suspend fun cancelAndJoin() {
        val activeJob = runHandle.job
        val activeMirror = runHandle.projectionJob
        try {
            clearPendingForCancellation()
        } finally {
            // Cleanup must complete even when inbox persistence fails or the caller is cancelled.
            withContext(NonCancellable) {
                activeJob?.cancel()
                activeMirror?.cancel()
                activeJob?.join()
                activeMirror?.join()
                stopApprovalProjection()
                if (runHandle.job === activeJob) runHandle.job = null
                if (runHandle.projectionJob === activeMirror) runHandle.projectionJob = null
                state.update { current ->
                    current.copy(kernel = current.kernel.copy(running = false))
                }
            }
        }
    }

    private fun clearPendingForCancellation() {
        interactions.cancelAll()
        val discarded = runHandle.pendingInputs.drain()
        try {
            if (discarded.isNotEmpty()) {
                eventLog.append(
                    LOCAL_AGENT_INBOX_EVENT_TYPE,
                    encodeLocalAgentInboxEvent(
                        action = "cancelled",
                        pending = runHandle.pendingInputs.snapshot(),
                        affected = discarded,
                    ),
                )
            }
        } finally {
            state.update { current ->
                current.copy(
                    work = current.work.copy(
                        pendingApproval = null,
                        pendingQuestion = null,
                        deviceApprovalLease = false,
                    ),
                    kernel = current.kernel.copy(queuedInputCount = 0),
                )
            }
        }
    }
}

internal fun projectJobSnapshotToRunStates(
    jobs: List<LocalJobInfo>,
    activeRuns: LocalWorkRunRegistry,
) {
    activeRuns.forEachEntry { sessionId, binding ->
        binding.state.update { current ->
            current.copy(
                work = current.work.copy(
                    jobs = projectExecutionJobs(LocalUsageMode.WORK, sessionId, jobs),
                ),
            )
        }
    }
}
