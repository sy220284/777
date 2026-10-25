package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalJobManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Codex agent/control/api.rs: resolving/observing a child and interrupting its current
 * activation are separate from spawning, message delivery and result settlement.
 * This controller uses the existing Job manager as its sole durable status/Inbox owner.
 * It never starts an agent by directly re-running its model turn.
 */
/** Canonical owner-sensitive admission for every standalone child-control operation. */
internal fun requireOwnedAgentInfo(
    available: List<LocalJobInfo>,
    sessionId: String,
    agentId: String,
): LocalJobInfo {
    require(sessionId.isNotBlank() && agentId.isNotBlank()) { "AGENT_TARGET_REQUIRED" }
    return available.singleOrNull {
        it.id == agentId && it.ownerSessionId == sessionId && it.isAgent
    } ?: error("AGENT_TARGET_NOT_FOUND：目标不属于当前 Work 会话或不是后台代理")
}

/** Cursor is a projection, never a new durable execution state or a receipt for model work. */
internal fun localAgentObservationCursor(info: LocalJobInfo): String =
    "${info.id}:${info.updatedAt}:${info.status}:${info.pendingMessageCount}"

internal fun validateAgentResumeAdmission(
    info: LocalJobInfo,
    snapshot: com.labteto.dshmobile.harness.jobs.JobSnapshot,
) {
    require(info.continuable && snapshot.resumeKind == "subagent_readonly") {
        "AGENT_RESUME_UNSUPPORTED：仅支持已有持久只读代理的安全恢复"
    }
    require(info.status in setOf("interrupted", "dormant", "waiting_slice")) {
        "AGENT_RESUME_STATE_INVALID：当前状态不能重新激活"
    }
    require(info.pendingMessageCount > 0 || info.status == "waiting_slice") {
        "AGENT_RESUME_INPUT_REQUIRED：需先有持久消息或等待中的安全执行片"
    }
}


internal class LocalAgentLifecycleController(
    private val jobs: LocalJobManager,
    private val persistentJobs: LocalPersistentJobRecoveryCoordinator,
    private val registry: LocalAgentRegistry,
) {
    private fun requireOwnedAgent(sessionId: String, agentId: String): LocalJobInfo {
        return registry.resolveOwnedChild(sessionId, agentId)
    }

    fun inspect(sessionId: String, agentId: String): String {
        val info = requireOwnedAgent(sessionId, agentId)
        val snapshot = jobs.snapshots().singleOrNull {
            it.id == agentId && it.ownerId == sessionId
        } ?: error("AGENT_SNAPSHOT_NOT_FOUND")
        return buildString {
            append("agent_id=").append(info.id)
            append("\nstatus=").append(info.status)
            append("\nowner_session=").append(sessionId)
            append("\ncontinuable=").append(info.continuable)
            append("\npending_messages=").append(info.pendingMessageCount)
            append("\nupdated_at=").append(info.updatedAt)
            append("\nobservation_cursor=").append(localAgentObservationCursor(info))
            append("\nresume_kind=").append(snapshot.resumeKind ?: "none")
            append("\nlatest_output=").append(snapshot.output.take(1_500))
            append("\nexecution_result_verified=false")
        }
    }

    suspend fun watch(
        sessionId: String,
        agentId: String,
        afterCursor: String,
        maxWaitMs: Int,
    ): String {
        require(afterCursor.isNotBlank()) { "AGENT_WATCH_CURSOR_REQUIRED：先调用 agent_inspect" }
        require(maxWaitMs in 0..20_000) { "AGENT_WATCH_TIMEOUT_INVALID" }
        val started = System.nanoTime()
        while (true) {
            currentCoroutineContext().ensureActive()
            val now = requireOwnedAgent(sessionId, agentId)
            if (localAgentObservationCursor(now) != afterCursor) {
                return "changed=true\n" + inspect(sessionId, agentId)
            }
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
            if (elapsed >= maxWaitMs) {
                return "changed=false\n" + inspect(sessionId, agentId)
            }
            delay(minOf(200L, maxWaitMs - elapsed))
        }
    }

    fun interrupt(sessionId: String, agentId: String): String {
        val info = registry.resolveStandaloneChild(sessionId, agentId)
        require(info.continuable) { "AGENT_NOT_CONTINUABLE：非持久代理不可安全暂停，可使用 agent_close 永久关闭" }
        return jobs.interruptContinuableAgent(agentId, sessionId)
    }

    fun close(sessionId: String, agentId: String): String {
        registry.resolveStandaloneChild(sessionId, agentId)
        return jobs.kill(agentId, sessionId)
    }

    fun resume(sessionId: String, agentId: String): String {
        val info = registry.resolveStandaloneChild(sessionId, agentId)
        val snapshot = jobs.snapshots().singleOrNull {
            it.id == agentId && it.ownerId == sessionId
        } ?: error("AGENT_SNAPSHOT_NOT_FOUND")
        validateAgentResumeAdmission(info, snapshot)
        persistentJobs.scheduleAgentResume(sessionId, agentId)
        return "agent_id=$agentId\nresume_requested=true\nresume_admitted=unverified" +
            "\nstatus_before=" + info.status +
            "\n说明=恢复必须通过原 Job 检查点、模型路由与授权安全闸门"
    }
}
