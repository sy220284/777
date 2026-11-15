package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.Flow

/** Owner-projected values only; a completed child result is not a verified requirement. */
data class UiWorkTeamMember(val id: String, val jobId: String, val name: String, val phase: String,
    val activity: String, val task: String?, val pendingMessages: Int, val error: String?)
data class UiWorkTeamTask(
    val id: String, val revision: Int, val subject: String, val status: String,
    val ownerName: String?, val blockedBy: List<String>, val ready: Boolean,
)
/** Operations permitted by the unified Work-owned Agent UI port. */
enum class UiWorkAgentOperation { INSPECT, SEND_MESSAGE, INTERRUPT, RESUME, CLOSE }

/** Client-visible child Agent record. Team and standalone share the durable Job identity. */
data class UiWorkAgent(val id: String, val label: String, val status: String,
    val continuable: Boolean, val teamManaged: Boolean)
data class UiWorkTeamSnapshot(val identity: UiSessionIdentity?, val members: List<UiWorkTeamMember>,
    val rebuilding: Boolean, val failure: String?, val tasks: List<UiWorkTeamTask> = emptyList(),
    val agents: List<UiWorkAgent> = emptyList())
sealed interface WorkTeamUiCommand {
    val identity: UiSessionIdentity
    data class SendMessage(override val identity: UiSessionIdentity, val memberId: String,
        val expectedJobId: String, val text: String) : WorkTeamUiCommand
    data class StopMember(override val identity: UiSessionIdentity, val memberId: String,
        val expectedJobId: String) : WorkTeamUiCommand
    data class StopTeam(override val identity: UiSessionIdentity, val expectedJobIds: Set<String>) : WorkTeamUiCommand
    /** The same typed command port handles standalone children; Team ownership remains explicit. */
    data class InspectAgent(override val identity: UiSessionIdentity, val agentId: String) : WorkTeamUiCommand
    data class SendAgentMessage(override val identity: UiSessionIdentity, val agentId: String,
        val text: String) : WorkTeamUiCommand
    data class InterruptAgent(override val identity: UiSessionIdentity, val agentId: String) : WorkTeamUiCommand
    data class CloseAgent(override val identity: UiSessionIdentity, val agentId: String) : WorkTeamUiCommand
    data class ResumeAgent(override val identity: UiSessionIdentity, val agentId: String) : WorkTeamUiCommand
    /** Explicit reassignment; rejects stale revisions or inactive target members. */
    data class ReassignTask(override val identity: UiSessionIdentity, val taskId: String,
        val expectedRevision: Int, val newMemberId: String?) : WorkTeamUiCommand
}
data class UiWorkTeamResult(val receipt: UiCommandReceipt, val message: String)
/** One Work Agent UI contract; includes Team and standalone child interaction. */
interface WorkAgentUiPort {
    val snapshots: Flow<UiWorkTeamSnapshot>
    fun snapshot(): UiWorkTeamSnapshot
    fun readOutput(identity: UiSessionIdentity, memberId: String, expectedJobId: String): UiWorkTeamResult
    suspend fun submit(command: WorkTeamUiCommand): UiWorkTeamResult
}

/** Source compatibility for existing Team screen callers; no second UI port implementation. */
typealias WorkTeamUiPort = WorkAgentUiPort
