package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.jobs.LocalJobRegistration

internal enum class LocalAgentAddressSpace { ALL, STANDALONE }

/**
 * Codex AgentControl.resolve identity admission: a durable child is identified by the
 * existing Job manager's authoritative registration, not its label or the current UI session.
 * Team member control stays in the authenticated Team namespace.
 */
internal fun requireAgentRegistration(
    registrations: List<LocalJobRegistration>,
    sessionId: String,
    agentId: String,
    addressSpace: LocalAgentAddressSpace = LocalAgentAddressSpace.ALL,
): LocalJobRegistration {
    require(sessionId.isNotBlank() && agentId.isNotBlank()) { "AGENT_TARGET_REQUIRED" }
    val agent = registrations.singleOrNull {
        it.info.id == agentId && it.info.ownerSessionId == sessionId &&
            it.info.isAgent && it.snapshot.id == agentId && it.snapshot.ownerId == sessionId
    } ?: error("AGENT_TARGET_NOT_FOUND：目标不属于当前 Work 会话或不是后台代理")
    if (addressSpace == LocalAgentAddressSpace.STANDALONE) {
        require(!LocalAgentTeamContract.isTeamJobId(agentId)) {
            "AGENT_TEAM_LIFECYCLE_VIA_TEAM_PORT：Team 成员必须经专用消息和控制协议"
        }
    }
    return agent
}

/**
 * Read-only Agent registry over one coherent Job manager snapshot. It has no second ledger,
 * no cached membership and no ability to load, awaken, interrupt or close an Agent.
 * Production list, message and lifecycle routes all resolve through this boundary.
 */
internal class LocalAgentRegistry(private val jobs: LocalJobManager) {
    private fun resolve(
        sessionId: String,
        agentId: String,
        space: LocalAgentAddressSpace,
    ): LocalJobRegistration = requireAgentRegistration(
        jobs.agentRegistrations(sessionId), sessionId, agentId, space,
    )

    fun resolveOwned(sessionId: String, agentId: String): LocalJobRegistration =
        resolve(sessionId, agentId, LocalAgentAddressSpace.ALL)

    fun resolveStandalone(sessionId: String, agentId: String): LocalJobRegistration =
        resolve(sessionId, agentId, LocalAgentAddressSpace.STANDALONE)

    /** Resolve a recorded but unloaded child without activating or re-registering it. */
    fun observeStandalone(sessionId: String, agentId: String): LocalAgentResidencyView =
        projectLocalAgentResidency(resolveStandalone(sessionId, agentId))

    fun resolveOwnedChild(sessionId: String, agentId: String): LocalJobInfo =
        resolveOwned(sessionId, agentId).info

    fun resolveStandaloneChild(sessionId: String, agentId: String): LocalJobInfo =
        resolveStandalone(sessionId, agentId).info

    /** Existing list format is preserved; Team members remain visible but not controllable here. */
    fun list(sessionId: String): String {
        require(sessionId.isNotBlank()) { "AGENT_TARGET_REQUIRED" }
        val agents = jobs.agentRegistrations(sessionId).map { registration ->
            "${registration.info.id} [${registration.info.status}] " +
                registration.info.label.removePrefix("子代理：")
        }
        return if (agents.isEmpty()) "没有后台代理" else agents.joinToString("\n")
    }
}
