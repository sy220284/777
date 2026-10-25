package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.jobs.LocalJobRegistration
import com.labteto.dshmobile.local.agent.captureLocalAgentParentLink
import com.labteto.dshmobile.harness.agent.AgentRunExecutionContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow

/** Observe the sole Job owner; queued scheduling and old persisted status are not load evidence. */
internal suspend fun awaitLocalAgentActivation(
    changes: StateFlow<Long>,
    baseline: Long,
    observe: () -> LocalJobRegistration,
    timeoutMillis: Long = 5_000L,
): LocalJobRegistration? = withTimeoutOrNull(timeoutMillis) {
    var observed: LocalJobRegistration
    while (true) {
        val version = changes.value
        observed = observe()
        if (observed.executionActive || observed.runtimeActivation > baseline) break
        if (observed.info.status in setOf("failed", "cancelled", "completed")) return@withTimeoutOrNull null
        changes.first { it != version }
    }
    observed
}

/**
 * Codex agent/control/api.rs: resolving/observing a child and interrupting its current
 * activation are separate from spawning, message delivery and result settlement.
 * This controller uses the existing Job manager as its sole durable status/Inbox owner.
 * It never starts an agent by directly re-running its model turn.
 */
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
    fun inspect(sessionId: String, agentId: String): String {
        val view = registry.observeOwned(sessionId, agentId)
        val registration = view.registration
        val info = registration.info
        val snapshot = registration.snapshot
        return buildString {
            append("agent_id=").append(info.id)
            append("\nstatus=").append(info.status)
            append("\ncontrol_namespace=").append(if (LocalAgentTeamContract.isTeamJobId(agentId)) "team" else "standalone")
            append("\nruntime_residency=").append(view.residency.name.lowercase())
            append("\nexecution_active=").append(registration.executionActive)
            append("\nruntime_activation=").append(registration.runtimeActivation)
            view.recordedParent?.let { parent ->
                append("\nrecorded_parent_run=").append(parent.parentRunId)
                append("\nrecorded_parent_slot=").append(parent.parentSlotId)
            }
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
        fun changed(): Boolean = localAgentObservationCursor(
            registry.resolveOwned(sessionId, agentId).info,
        ) != afterCursor
        if (!changed() && maxWaitMs > 0) {
            withTimeoutOrNull(maxWaitMs.toLong()) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    // Capture notification version BEFORE observing: a change in between the
                    // read and subscription is retained by StateFlow and cannot be missed.
                    val version = jobs.changes.value
                    if (changed()) break
                    jobs.changes.first { it != version }
                }
            }
        }
        return "changed=${changed()}\n" + inspect(sessionId, agentId)
    }

    fun interrupt(sessionId: String, agentId: String): String {
        val info = registry.resolveStandalone(sessionId, agentId).info
        require(info.continuable) { "AGENT_NOT_CONTINUABLE：非持久代理不可安全暂停，可使用 agent_close 永久关闭" }
        return jobs.interruptContinuableAgent(agentId, sessionId)
    }

    fun close(sessionId: String, agentId: String): String {
        registry.resolveStandalone(sessionId, agentId)
        return jobs.kill(agentId, sessionId)
    }

    /**
     * Codex ensure_child_loaded: preserve the original durable Job ID and mailbox.
     * The invoking coroutine must own the recorded direct-parent slot. Scheduling
     * remains a request to the existing coordinator and is not proof of execution.
     */
    suspend fun ensureChildLoaded(sessionId: String, agentId: String): String {
        val caller = captureLocalAgentParentLink()
        val view = registry.observeStandalone(sessionId, agentId)
        validateImmediateAgentParentForLoad(view.recordedParent, caller, sessionId)
        when (view.residency) {
            LocalAgentRuntimeResidency.ACTIVE_JOB ->
                return "agent_id=$agentId\nchild_loaded=true\nload_action=already_active"
            LocalAgentRuntimeResidency.TERMINAL -> error(
                "AGENT_CHILD_TERMINAL：终态代理不能重新加载"
            )
            LocalAgentRuntimeResidency.UNLOADED -> Unit
            LocalAgentRuntimeResidency.LOADING -> Unit
        }
        if (view.residency == LocalAgentRuntimeResidency.UNLOADED) {
            validateAgentResumeAdmission(view.registration.info, view.registration.snapshot)
        }
        // Handoff is committed under the same live run-slot lock that revokes
        // previous activations. A displaced caller cannot race a new parent turn.
        val liveOwner = currentCoroutineContext()[AgentRunExecutionContext]
        if (view.residency == LocalAgentRuntimeResidency.UNLOADED && liveOwner != null) {
            liveOwner.commitCurrent(sessionId) {
                persistentJobs.scheduleAgentResume(sessionId, agentId)
            }
        } else if (view.residency == LocalAgentRuntimeResidency.UNLOADED) {
            persistentJobs.scheduleAgentResume(sessionId, agentId)
        }
        val activated = awaitLocalAgentActivation(jobs.changes, view.registration.runtimeActivation,
            observe = { registry.resolveStandalone(sessionId, agentId) })
        currentCoroutineContext().ensureActive()
        liveOwner?.requireCurrent(sessionId)
        val current = activated ?: registry.resolveStandalone(sessionId, agentId)
        return "agent_id=$agentId\nload_requested=true\nchild_loaded=${activated != null}" +
            "\nload_admitted=" + (if (activated != null) "true" else "unverified") +
            "\nexecution_active=" + current.executionActive +
            "\nruntime_activation=" + current.runtimeActivation +
            "\nstatus=" + current.info.status +
            "\nexecution_result_verified=false" +
            "\nparent_slot=" + (view.recordedParent?.parentSlotId ?: "legacy_unknown") +
            "\n说明=加载由原持久 Job 恢复入口处理，身份、邮箱与模型路由不替换"
    }

    fun resume(sessionId: String, agentId: String): String {
        val registration = registry.resolveStandalone(sessionId, agentId)
        val info = registration.info
        validateAgentResumeAdmission(info, registration.snapshot)
        persistentJobs.scheduleAgentResume(sessionId, agentId)
        return "agent_id=$agentId\nresume_requested=true\nresume_admitted=unverified" +
            "\nstatus_before=" + info.status +
            "\n说明=恢复必须通过原 Job 检查点、模型路由与授权安全闸门"
    }
}
