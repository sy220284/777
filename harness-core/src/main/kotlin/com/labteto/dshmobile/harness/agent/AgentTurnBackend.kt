package com.labteto.dshmobile.harness.agent

import kotlinx.serialization.Serializable

/** The caller retains model credentials, tool authority, resource admission and durable events. */
@Serializable
data class AgentBackendRequest(
    val turnId: String,
    val input: String,
    val history: List<AgentMessage>,
    val maxSteps: Int,
)

class AgentBackendServices(
    val model: AgentModel,
    val tools: AgentToolExecutor,
    val batch: AgentToolBatchExecutor,
    val isParallel: (AgentToolCall) -> Boolean,
    val events: AgentEventSink,
    val extend: (suspend (Int, Int) -> Int?)?,
)

fun interface AgentTurnBackend {
    suspend fun run(request: AgentBackendRequest, services: AgentBackendServices): AgentRunResult
}
