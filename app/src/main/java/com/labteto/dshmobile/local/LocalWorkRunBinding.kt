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
    val executionBudget = LocalWorkExecutionBudget()
    val routeCircuitBreaker = LocalModelRouteCircuitBreaker()

    @Volatile
    var transcriptProjectionCursor: Long? = initialTranscriptProjectionCursor

    @Volatile
    var job: Job? = null

    @Volatile
    var mirrorJob: Job? = null

    @Volatile
    var turnsSinceModelHistoryCheckpoint: Int = 0

    /** At most one automatic continuation is allowed for an admitted Responses stream interruption. */
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
                running = false,
                pendingApproval = null,
                pendingQuestion = null,
                queuedInputCount = 0,
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
        current.copy(jobs = projectExecutionJobs(current.usageMode, current.sessionId, jobs))
    }
    activeRuns.forEach { (sessionId, binding) ->
        binding.state.update { current ->
            current.copy(jobs = projectExecutionJobs(current.usageMode, sessionId, jobs))
        }
    }
}
