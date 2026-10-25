package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.jobs.JobSnapshot
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalJobManager

/**
 * An Agent registration is the same durable Job viewed through its public and recovery
 * projections. Neither a Job label alone nor a forged payload grants control of an Agent.
 * There is deliberately no second mutable registry or independently persisted status.
 */
internal data class LocalAgentRegistration(
    val info: LocalJobInfo,
    val snapshot: JobSnapshot,
)

internal enum class LocalAgentAddressSpace { ALL, STANDALONE }

/** Resolve the exact durable identity before routing any read or control command. */
internal fun requireAgentRegistration(
    infos: List<LocalJobInfo>,
    snapshots: List<JobSnapshot>,
    sessionId: String,
    agentId: String,
    addressSpace: LocalAgentAddressSpace = LocalAgentAddressSpace.ALL,
): LocalAgentRegistration {
    require(sessionId.isNotBlank() && agentId.isNotBlank()) { "AGENT_TARGET_REQUIRED" }
    val info = infos.singleOrNull {
        it.id == agentId && it.ownerSessionId == sessionId && it.isAgent
    } ?: error("AGENT_TARGET_NOT_FOUND：目标不属于当前 Work 会话或不是后台代理")
    val snapshot = snapshots.singleOrNull {
        it.id == agentId && it.ownerId == sessionId
    } ?: error("AGENT_REGISTRATION_INCONSISTENT：无法找到同一会话的持久 Job 快照")
    require(snapshot.label == info.label && snapshot.status == info.status &&
        snapshot.continuable == info.continuable) {
        "AGENT_REGISTRATION_INCONSISTENT：Job 身份与运行快照不一致"
    }
    if (addressSpace == LocalAgentAddressSpace.STANDALONE) {
        require(!LocalAgentTeamContract.isTeamJobId(agentId)) {
            "AGENT_TEAM_LIFECYCLE_VIA_TEAM_PORT：Team 成员必须经专用消息和控制协议"
        }
    }
    return LocalAgentRegistration(info, snapshot)
}

/**
 * Codex AgentControl.resolve/list inspect identities without loading or waking children.
 * All entry points consume the same canonical Job snapshot pair. Team member commands
 * remain exclusively owned by Team; this registry does not issue runtime actions.
 */
internal class LocalAgentRegistry(private val jobs: LocalJobManager) {
    private fun resolve(
        sessionId: String,
        agentId: String,
        space: LocalAgentAddressSpace,
    ): LocalAgentRegistration = requireAgentRegistration(
        jobs.snapshotInfos(), jobs.snapshots(), sessionId, agentId, space,
    )

    fun resolveOwned(sessionId: String, agentId: String): LocalAgentRegistration =
        resolve(sessionId, agentId, LocalAgentAddressSpace.ALL)

    fun resolveStandalone(sessionId: String, agentId: String): LocalAgentRegistration =
        resolve(sessionId, agentId, LocalAgentAddressSpace.STANDALONE)

    fun resolveOwnedChild(sessionId: String, agentId: String): LocalJobInfo =
        resolveOwned(sessionId, agentId).info

    fun resolveStandaloneChild(sessionId: String, agentId: String): LocalJobInfo =
        resolveStandalone(sessionId, agentId).info

    /** Preserve the original list format while enforcing the same identity admission. */
    fun list(sessionId: String): String {
        require(sessionId.isNotBlank()) { "AGENT_TARGET_REQUIRED" }
        val infos = jobs.snapshotInfos()
        val snapshots = jobs.snapshots()
        val agents = infos.filter { it.ownerSessionId == sessionId && it.isAgent }
            .map { info ->
                val registered = requireAgentRegistration(
                    infos, snapshots, sessionId, info.id,
                )
                "${registered.info.id} [${registered.info.status}] " +
                    registered.info.label.removePrefix("子代理：")
            }
        return agents.takeIf(List<String>::isNotEmpty)?.joinToString("\n") ?: "没有后台代理"
    }
}
