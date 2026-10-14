package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.Flow

/** Owner-projected values only; a completed child result is not a verified requirement. */
data class UiWorkTeamMember(val id: String, val jobId: String, val name: String, val phase: String,
    val activity: String, val task: String?, val pendingMessages: Int, val error: String?)
data class UiWorkTeamSnapshot(val identity: UiSessionIdentity?, val members: List<UiWorkTeamMember>,
    val rebuilding: Boolean, val failure: String?)
sealed interface WorkTeamUiCommand {
    val identity: UiSessionIdentity
    data class SendMessage(override val identity: UiSessionIdentity, val memberId: String,
        val expectedJobId: String, val text: String) : WorkTeamUiCommand
    data class StopMember(override val identity: UiSessionIdentity, val memberId: String,
        val expectedJobId: String) : WorkTeamUiCommand
    data class StopTeam(override val identity: UiSessionIdentity, val expectedJobIds: Set<String>) : WorkTeamUiCommand
}
data class UiWorkTeamResult(val receipt: UiCommandReceipt, val message: String)
interface WorkTeamUiPort {
    val snapshots: Flow<UiWorkTeamSnapshot>
    fun snapshot(): UiWorkTeamSnapshot
    fun readOutput(identity: UiSessionIdentity, memberId: String, expectedJobId: String): UiWorkTeamResult
    suspend fun submit(command: WorkTeamUiCommand): UiWorkTeamResult
}
