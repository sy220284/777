package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject

/**
 * Shared mutable facts for one local Agent run slot.
 *
 * Product Features may retain a reference to this handle, but the queue, Job and model-history
 * facts themselves live in Shared Runtime. A visible foreground slot can be rebound only after its
 * previous Job has stopped; detached Work creates a dedicated handle bound to its Session.
 */
internal class LocalAgentRunHandle(
    initialSessionId: String? = null,
    initialHistory: List<JsonObject> = emptyList(),
    initialTranscriptProjectionCursor: Long? = null,
    maxPendingInputs: Int,
) {
    val lock: Any = Any()
    val modelHistory = LocalModelHistoryBuffer().apply { reset(initialHistory) }
    val pendingInputs = AgentInputQueue(maxPendingInputs)

    @Volatile
    var sessionId: String? = initialSessionId
        private set

    @Volatile
    var job: Job? = null

    /** Optional projection/observer Job owned by the same run lifecycle. */
    @Volatile
    var projectionJob: Job? = null

    @Volatile
    var transcriptProjectionCursor: Long? = initialTranscriptProjectionCursor

    @Volatile
    var turnsSinceModelHistoryCheckpoint: Int = 0

    internal fun hasLiveJob(): Boolean = job?.isCompleted == false

    /**
     * Rebind the reusable visible slot after its previous owner has stopped.
     * Durable history/inbox are restored by Session load after this reset.
     */
    internal fun rebindSession(nextSessionId: String) {
        require(nextSessionId.isNotBlank()) { "运行句柄会话编号不能为空" }
        synchronized(lock) {
            check(!hasLiveJob()) { "运行中的句柄不能切换会话" }
            job = null
            projectionJob?.cancel()
            projectionJob = null
            pendingInputs.drain()
            modelHistory.reset()
            transcriptProjectionCursor = null
            turnsSinceModelHistoryCheckpoint = 0
            sessionId = nextSessionId
        }
    }
}
