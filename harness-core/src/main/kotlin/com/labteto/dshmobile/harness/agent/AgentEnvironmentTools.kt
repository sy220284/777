package com.labteto.dshmobile.harness.agent

import com.labteto.dshmobile.harness.tools.ToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Only the already-admitted invocation crosses this boundary; no model/account secrets. */
@Serializable
data class AgentEnvironmentToolRequest(
    val name: String,
    val input: JsonObject,
    val rawArguments: String,
    val allowMutation: Boolean,
    val sessionId: String?,
    val callId: String,
    val executionId: String,
)

fun interface AgentEnvironmentTools {
    suspend fun execute(request: AgentEnvironmentToolRequest): ToolResult
    suspend fun close() {}
}
