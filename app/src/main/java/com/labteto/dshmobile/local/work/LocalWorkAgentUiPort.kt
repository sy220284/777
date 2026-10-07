package com.labteto.dshmobile.local.work

internal data class LocalWorkAgentUiResult(
    val accepted: Boolean,
    val message: String,
)

/** Narrow Work-owned actions surfaced to the Run Center UI. */
internal interface LocalWorkAgentUiPort {
    suspend fun startBackgroundAgent(task: String): LocalWorkAgentUiResult
    suspend fun sendMessage(agentId: String, message: String): LocalWorkAgentUiResult
}
