package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.jobs.JobSnapshot
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalJobManager

/**
 * The durable Job snapshot is the single authoritative registration of a local Agent.
 * Resolving identity must not start a model turn, reload a Job or create another ledger.
 */
internal data class LocalAgentRegistration(val snapshot: JobSnapshot) {
    val info: LocalJobInfo get() = LocalJobInfo(
        id = snapshot.id,
        label = snapshot.label,
        status = snapshot.status,
        ownerSessionId = snapshot.ownerId,
        isAgent = true,
        canMessage = snapshot.status in setOf("running", "interrupted", "waiting_slice") ||
            (snapshot.continuable && snapshot.status in setOf("dormant", "completed")),
        continuable = snapshot.continuable,
        pendingMessageCount = snapshot.inbox.size,
        updatedAt = snapshot.updatedAt,
    )
}

internal enum class LocalAgentAddressSpace { ALL, STANDALONE }

/** One atomic snapshot read prevents disagreement between JobInfo and JobSnapshot. */
internal fun requireAgentRegistration(
    snapshots: List<JobSnapshot>,
    sessionId: String,
    agentId: String,
    addressSpace: LocalAgentAddressSpace = LocalAgentAddressSpace.ALL,
): LocalAgentRegistration {
    require(sessionId.isNotBlank() && agentId.isNotBlank()) { "AGENT_TARGET_REQUIRED" }
    val snapshot = snapshots.singleOrNull {
        it.id == agentId && it.ownerId == sessionId && it.label.startsWith("子代理：")
    } ?: error("AGENT_TARGET_NOT_FOUND：目标不属于当前 Work 会话或不是后台代理")
    if (addressSpace == LocalAgentAddressSpace.STANDALONE) {
        require(!LocalAgentTeamContract.isTeamJobId(agentId)) {
            "AGENT_TEAM_LIFECYCLE_VIA_TEAM_PORT：Team 成员必须经专用消息和控制协议"
        }
    }
    return LocalAgentRegistration(snapshot)
}

/**
 * Codex AgentControl.resolve/list semantics over the existing persistent Job registry.
 * Team control has a distinct authenticated address space. No second state is stored.
 */
internal class LocalAgentRegistry(private val jobs: LocalJobManager) {
    private fun resolve(
        sessionId: String,
        agentId: String,
        space: LocalAgentAddressSpace,
    ): LocalAgentRegistration =
        requireAgentRegistration(jobs.snapshots(), sessionId, agentId, space)

    fun resolveOwned(sessionId: String, agentId: String): LocalAgentRegistration =
        resolve(sessionId, agentId, LocalAgentAddressSpace.ALL)

    fun resolveStandalone(sessionId: String, agentId: String): LocalAgentRegistration =
        resolve(sessionId, agentId, LocalAgentAddressSpace.STANDALONE)

    fun resolveOwnedChild(sessionId: String, agentId: String): LocalJobInfo =
        resolveOwned(sessionId, agentId).info

    fun resolveStandaloneChild(sessionId: String, agentId: String): LocalJobInfo =
        resolveStandalone(sessionId, agentId).info

    /** Keep the old tool output format; it includes Team members for observation only. */
    fun list(sessionId: String): String {
        require(sessionId.isNotBlank()) { "AGENT_TARGET_REQUIRED" }
        val snapshots = jobs.snapshots()
        val agents = snapshots.filter {
            it.ownerId == sessionId && it.label.startsWith("子代理：")
        }.map { snapshot ->
            val registration = requireAgentRegistration(snapshots, sessionId, snapshot.id)
            "${registration.info.id} [${registration.info.status}] " +
                registration.info.label.removePrefix("子代理：")
        }
        return if (agents.isEmpty()) "没有后台代理" else agents.joinToString("\n")
    }
}
