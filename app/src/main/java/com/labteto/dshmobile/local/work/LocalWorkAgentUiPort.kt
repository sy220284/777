package com.labteto.dshmobile.local.work

internal data class LocalWorkAgentUiResult(
    val accepted: Boolean,
    val message: String,
)

/** Work-owned commands for the existing Agent team conversation controls. */
internal interface LocalWorkAgentUiPort {
    suspend fun sendTeamMessage(memberId: String, message: String): LocalWorkAgentUiResult
    suspend fun stopTeamMember(memberId: String): LocalWorkAgentUiResult
    suspend fun stopTeam(): LocalWorkAgentUiResult
}
