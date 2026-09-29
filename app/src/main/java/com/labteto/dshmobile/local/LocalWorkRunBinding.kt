package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
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

    @Volatile
    var transcriptProjectionCursor: Long? = initialTranscriptProjectionCursor

    @Volatile
    var job: Job? = null

    @Volatile
    var mirrorJob: Job? = null

    @Volatile
    var turnsSinceModelHistoryCheckpoint: Int = 0

    val transcriptRuntime = LocalTranscriptRuntime(
        state = state,
        pruneToolResult = pruneToolResult,
        runtimeWindowMessages = LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES,
        onProjected = { sequence ->
            transcriptProjectionCursor = maxOf(transcriptProjectionCursor ?: -1L, sequence)
        },
    )
}

/**
 * Cancels session-bound Work runtimes before their sessions are physically deleted.
 *
 * Deletion is stronger than ordinary session switching: queued input and pending interactions are
 * discarded, every matching run is cancelled and joined, and only then is its binding detached.
 * This guarantees no deleted event log can be recreated by a still-running Work coroutine.
 */
internal suspend fun cancelWorkRunsForDeletedSessions(
    activeRuns: ConcurrentHashMap<String, LocalWorkRunBinding>,
    sessionIds: Set<String>,
    runStateLock: Any,
) {
    if (sessionIds.isEmpty()) return
    val bindings = synchronized(runStateLock) {
        sessionIds.mapNotNull(activeRuns::get).distinct()
    }
    if (bindings.isEmpty()) return

    val jobs = bindings.mapNotNull { binding ->
        binding.interactions.cancelAll()
        binding.pendingInputs.drain()
        binding.state.update {
            it.copy(
                running = false,
                queuedInputCount = 0,
                pendingApproval = null,
                pendingQuestion = null,
                deviceApprovalLease = false,
            )
        }
        binding.job?.also { it.cancel() }
    }
    jobs.forEach { it.join() }

    synchronized(runStateLock) {
        bindings.forEach { binding ->
            if (activeRuns.remove(binding.sessionId, binding)) {
                binding.mirrorJob?.cancel()
                binding.mirrorJob = null
                binding.job = null
            }
        }
    }
}
