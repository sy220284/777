package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalJobManager

/**
 * Read-only Agent identity resolution over the existing durable Job registry.
 * Codex agent/api.rs::resolve is explicitly separate from activation and delivery.
 * This resolver does not create a second membership table or start/awaken any Job.
 */
internal class LocalAgentRegistry(private val jobs: LocalJobManager) {
    /** All direct child controls must be scoped to the invoking Work Session. */
    fun resolveOwnedChild(sessionId: String, agentId: String): LocalJobInfo =
        requireOwnedAgentInfo(jobs.snapshotInfos(), sessionId, agentId)

    /**
     * Team addresses and Job IDs are different routing namespaces.
     * Team communication/control always goes through its authenticated member/Lead path.
     */
    fun resolveStandaloneChild(sessionId: String, agentId: String): LocalJobInfo {
        val agent = resolveOwnedChild(sessionId, agentId)
        require(!LocalAgentTeamContract.isTeamJobId(agent.id)) {
            "AGENT_TEAM_LIFECYCLE_VIA_TEAM_PORT：Team 成员必须经专用消息和控制协议"
        }
        return agent
    }
}
