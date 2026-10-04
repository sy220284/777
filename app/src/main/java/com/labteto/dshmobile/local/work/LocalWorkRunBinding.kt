package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * One Work turn's session-bound mutable runtime.
 *
 * A Work run owns these references from the moment it starts. The visible UI may switch to another
 * conversation while the run continues; model history, transcript projection, interaction waits and
 * optional-tool activation therefore never follow the UI's current session.
 */
internal class LocalWorkRunBinding(
    val sessionId: String,
    initialState: LocalHarnessState,
    initialHistory: List<kotlinx.serialization.json.JsonObject>,
    val eventLog: LocalSessionEventLog,
    initialTranscriptProjectionCursor: Long?,
    maxPendingInputs: Int,
    pruneToolResult: (String) -> String,
) {
    val state = MutableStateFlow(initialState.copy(sessionId = sessionId))
    val modelHistory = LocalModelHistoryBuffer().apply { reset(initialHistory) }
    val pendingInputs = AgentInputQueue(maxPendingInputs)
    val interactions = LocalInteractionCoordinator(state)
    val enabledOptionalTools = linkedSetOf<String>()
    /** Main Agent and every child spawned by this Work run share one admission budget and breaker. */
    val executionControl = LocalWorkExecutionControl()

    @Volatile
    var transcriptProjectionCursor: Long? = initialTranscriptProjectionCursor

    @Volatile
    var job: Job? = null

    @Volatile
    var mirrorJob: Job? = null

    @Volatile
    var turnsSinceModelHistoryCheckpoint: Int = 0

    /** Automatic continuation is bounded by the shared Work continuation policy. */
    @Volatile
    var automaticContinuationCount: Int = 0

    @Volatile
    var continuationParentRunId: String? = null

    val transcriptRuntime = LocalTranscriptRuntime(
        state = state,
        pruneToolResult = pruneToolResult,
        runtimeWindowMessages = LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES,
        onProjected = { sequence ->
            transcriptProjectionCursor = maxOf(transcriptProjectionCursor ?: -1L, sequence)
        },
    )

    /** Tear down only this session-owned runtime; other Work conversations keep running. */
    suspend fun cancelAndJoin() {
        interactions.cancelAll()
        val discarded = pendingInputs.drain()
        if (discarded.isNotEmpty()) {
            eventLog.append(
                LOCAL_AGENT_INBOX_EVENT_TYPE,
                encodeLocalAgentInboxEvent(
                    action = "cancelled",
                    pending = emptyList(),
                    affected = discarded,
                ),
            )
        }
        state.update {
            it.copy(
                work = it.work.copy(
                    pendingApproval = null,
                    pendingQuestion = null,
                ),
                kernel = it.kernel.copy(
                    running = false,
                    queuedInputCount = 0,
                ),
                deviceApprovalLease = false,
            )
        }
        val activeJob = job.also { job = null }
        val activeMirror = mirrorJob.also { mirrorJob = null }
        activeJob?.cancelAndJoin()
        activeMirror?.cancelAndJoin()
    }
}

internal fun projectJobSnapshotToSessionStates(
    jobs: List<LocalJobInfo>,
    visibleState: MutableStateFlow<LocalHarnessState>,
    activeRuns: java.util.concurrent.ConcurrentHashMap<String, LocalWorkRunBinding>,
) {
    visibleState.update { current ->
        current.copy(
            work = current.work.copy(
                jobs = projectExecutionJobs(current.usageMode, current.sessionId, jobs),
            ),
        )
    }
    activeRuns.forEach { (sessionId, binding) ->
        binding.state.update { current ->
            current.copy(
                work = current.work.copy(
                    jobs = projectExecutionJobs(current.usageMode, sessionId, jobs),
                ),
            )
        }
    }
}


internal fun mirrorLocalWorkRunState(
    currentSessionId: String,
    visibleState: MutableStateFlow<LocalHarnessState>,
    binding: LocalWorkRunBinding,
) {
    if (currentSessionId != binding.sessionId || visibleState.value.sessionId != binding.sessionId) return
    val run = binding.state.value
    visibleState.update { visible ->
        if (visible.sessionId != binding.sessionId) {
            visible
        } else {
            visible.copy(
                messages = run.messages,
                transcriptIndex = run.transcriptIndex,
                work = run.work,
                deviceApprovalLease = run.deviceApprovalLease,
                kernel = visible.kernel.copy(
                    running = run.kernel.running,
                    queuedInputCount = run.kernel.queuedInputCount,
                    contextChars = run.kernel.contextChars,
                    contextBudgetChars = run.kernel.contextBudgetChars,
                ),
                error = run.error,
            )
        }
    }
}
