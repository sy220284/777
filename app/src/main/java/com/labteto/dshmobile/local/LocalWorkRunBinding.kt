package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

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

internal suspend fun cancelWorkRunBindingsForSessions(
    activeRuns: ConcurrentHashMap<String, LocalWorkRunBinding>,
    sessionIds: Set<String>,
) {
    if (sessionIds.isEmpty()) return
    val targets = sessionIds.mapNotNull { id ->
        activeRuns[id]?.let { id to it }
    }
    targets.forEach { (_, binding) ->
        binding.interactions.cancelAll()
        binding.pendingInputs.drain()
        binding.state.value = binding.state.value.copy(
            queuedInputCount = 0,
            pendingApproval = null,
            pendingQuestion = null,
        )
        binding.job?.cancel()
    }
    targets.forEach { (id, binding) ->
        binding.job?.join()
        if (activeRuns.remove(id, binding)) {
            binding.mirrorJob?.cancel()
            binding.mirrorJob = null
            binding.job = null
        }
    }
}
