package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.LocalAgentDelegationKind
import com.labteto.dshmobile.local.agent.LocalSubagentHistoryMode
import com.labteto.dshmobile.local.agent.buildLocalDelegatedLaunchSpec
import com.labteto.dshmobile.local.agent.captureLocalAgentParentLink
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import kotlinx.serialization.json.JsonObject

/** Typed control command. Model schemas are parsed only by the model-facing adapter. */
internal enum class LocalAgentDispatchKind { READONLY_FOREGROUND, READONLY_BACKGROUND, FORK }

internal data class LocalAgentDispatchRequest(
    val kind: LocalAgentDispatchKind,
    val task: String,
    val instructions: String,
    val modelOverride: String?,
    val maxSteps: Int,
    val parentToolCallId: String,
    val maxDepth: Int = 1,
    val allowMutation: Boolean = false,
    val historyMode: LocalSubagentHistoryMode = LocalSubagentHistoryMode.ISOLATED,
    val virtualScreen: Boolean = false,
    val toolAllowlist: Set<String>? = null,
    val outputSchema: JsonObject? = null,
) {
    init {
        require(task.isNotBlank() && parentToolCallId.isNotBlank()) { "AGENT_DELEGATION_REQUEST_INVALID" }
        require(maxDepth in 1..3) { "AGENT_DELEGATION_DEPTH_INVALID" }
        require(maxDepth == 1 || kind == LocalAgentDispatchKind.READONLY_FOREGROUND) {
            "AGENT_RECURSION_ONLY_FOREGROUND_READONLY"
        }
        require(maxDepth == 1 || !virtualScreen) { "AGENT_RECURSION_VIRTUAL_SCREEN_BLOCKED" }
        require(kind == LocalAgentDispatchKind.FORK || !allowMutation) { "READONLY_DELEGATION_CANNOT_MUTATE" }
        require(kind != LocalAgentDispatchKind.FORK || historyMode == LocalSubagentHistoryMode.INHERIT_PARENT) {
            "FORK_REQUIRES_PARENT_HISTORY"
        }
        require(kind != LocalAgentDispatchKind.READONLY_BACKGROUND ||
            historyMode == LocalSubagentHistoryMode.ISOLATED) { "BACKGROUND_HISTORY_MUST_BE_ISOLATED" }
    }
}

/**
 * Codex-style lifecycle port for Work child Agents.
 * Owner of execution facts remains the existing Job/Session/Run/Tool capabilities.
 */
internal interface LocalAgentControlPort {
    suspend fun delegate(binding: LocalWorkRunBinding, request: LocalAgentDispatchRequest): String
    fun list(sessionId: String): String
    fun tree(binding: LocalWorkRunBinding, maxNodes: Int): String
    fun send(
        sessionId: String,
        agentId: String,
        message: String,
        mode: LocalAgentMessageDeliveryMode = LocalAgentMessageDeliveryMode.TRIGGER_TURN,
    ): String
    /**
     * Inspect only an owned, actually registered persistent Agent. A Job label alone never
     * authorizes control over another session or an unrelated background process.
     */
    fun inspect(sessionId: String, agentId: String): String
    /** Stop this Activation without deleting the resumable Agent identity/Inbox. */
    fun interrupt(sessionId: String, agentId: String): String
    /** Terminal cancellation; unlike interrupt, this clears the durable Inbox. */
    fun close(sessionId: String, agentId: String): String
    /** Request admission through the existing trusted recovery coordinator, not direct replay. */
    fun resume(sessionId: String, agentId: String): String
}

/** Runtime implementation; no model-visible JSON parsing and no parallel persistence store. */
internal class LocalWorkAgentControlRuntime(
    private val jobs: LocalJobManager,
    private val subagents: LocalWorkSubagentRuntime,
    private val persistentJobs: LocalPersistentJobRecoveryCoordinator,
) : LocalAgentControlPort {
    override suspend fun delegate(binding: LocalWorkRunBinding, request: LocalAgentDispatchRequest): String {
        val parent = captureLocalAgentParentLink(request.parentToolCallId)
        require(parent == null || parent.sessionId == binding.sessionId) { "AGENT_PARENT_SESSION_MISMATCH" }
        return when (request.kind) {
            LocalAgentDispatchKind.READONLY_BACKGROUND -> persistentJobs.startReadonlySubagent(
                task = request.task,
                model = request.modelOverride,
                maxSteps = request.maxSteps,
                instructions = request.instructions,
                virtualScreen = request.virtualScreen,
                outputSchema = request.outputSchema,
                toolAllowlist = request.toolAllowlist,
                parentToolCallId = request.parentToolCallId,
                sessionId = binding.sessionId,
                boundState = binding.aggregateSnapshot(),
                historySnapshot = binding.runHandle.modelHistory::snapshot,
            )
            LocalAgentDispatchKind.READONLY_FOREGROUND -> subagents.runner(binding).run(
                buildLocalDelegatedLaunchSpec(
                    kind = LocalAgentDelegationKind.FOREGROUND_READONLY,
                    task = request.task,
                    instructions = request.instructions,
                    modelOverride = request.modelOverride,
                    maxSteps = request.maxSteps,
                    parentCallId = request.parentToolCallId.takeIf {
                        request.historyMode == LocalSubagentHistoryMode.INHERIT_PARENT
                    },
                    historyMode = request.historyMode,
                    maxDepth = request.maxDepth,
                    virtualScreen = request.virtualScreen,
                    toolAllowlist = request.toolAllowlist,
                    outputSchema = request.outputSchema,
                    parentLink = parent,
                ),
            )
            LocalAgentDispatchKind.FORK -> subagents.runner(binding).run(
                buildLocalDelegatedLaunchSpec(
                    kind = LocalAgentDelegationKind.FORK,
                    task = request.task,
                    instructions = request.instructions,
                    modelOverride = request.modelOverride,
                    maxSteps = request.maxSteps,
                    parentCallId = request.parentToolCallId,
                    allowMutation = request.allowMutation,
                    toolAllowlist = request.toolAllowlist,
                    outputSchema = request.outputSchema,
                    parentLink = parent,
                ),
            )
        }
    }

    override fun list(sessionId: String): String = jobs.listAgents(sessionId)
    override fun tree(binding: LocalWorkRunBinding, maxNodes: Int): String =
        readLocalAgentTree(binding.eventLog, maxNodes).render()
    override fun send(
        sessionId: String,
        agentId: String,
        message: String,
        mode: LocalAgentMessageDeliveryMode,
    ): String = persistentJobs.sendMessage(agentId, message, sessionId, mode)
    private fun requireOwnedAgent(sessionId: String, agentId: String): LocalJobInfo {
        require(sessionId.isNotBlank() && agentId.isNotBlank()) { "AGENT_TARGET_REQUIRED" }
        return jobs.snapshotInfos().singleOrNull {
            it.id == agentId && it.ownerSessionId == sessionId && it.isAgent
        } ?: error("AGENT_TARGET_NOT_FOUND：目标不属于当前 Work 会话或不是后台代理")
    }

    override fun inspect(sessionId: String, agentId: String): String {
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
            append("\nresume_kind=").append(snapshot.resumeKind ?: "none")
            append("\nlatest_output=").append(snapshot.output.take(1_500))
            append("\nexecution_result_verified=false")
        }
    }

    override fun interrupt(sessionId: String, agentId: String): String {
        val info = requireOwnedAgent(sessionId, agentId)
        require(info.continuable) { "AGENT_NOT_CONTINUABLE：非持久代理不可安全暂停，可使用 agent_close 永久关闭" }
        return jobs.interruptContinuableAgent(agentId, sessionId)
    }

    override fun close(sessionId: String, agentId: String): String {
        requireOwnedAgent(sessionId, agentId)
        return jobs.kill(agentId, sessionId)
    }

    override fun resume(sessionId: String, agentId: String): String {
        val info = requireOwnedAgent(sessionId, agentId)
        val snapshot = jobs.snapshots().singleOrNull {
            it.id == agentId && it.ownerId == sessionId
        } ?: error("AGENT_SNAPSHOT_NOT_FOUND")
        require(info.continuable && snapshot.resumeKind == "subagent_readonly") {
            "AGENT_RESUME_UNSUPPORTED：仅支持已有持久只读代理的安全恢复"
        }
        require(info.status in setOf("interrupted", "dormant", "waiting_slice")) {
            "AGENT_RESUME_STATE_INVALID：当前状态不能重新激活"
        }
        require(info.pendingMessageCount > 0 || info.status == "waiting_slice") {
            "AGENT_RESUME_INPUT_REQUIRED：需先有持久消息或等待中的安全执行片"
        }
        persistentJobs.schedule(sessionId, rootSliceAdmitted = true)
        return "agent_id=$agentId\nresume_requested=true\nresume_admitted=unverified" +
            "\nstatus_before=" + info.status +
            "\n说明=恢复必须通过原 Job 检查点、模型路由与授权安全闸门"
    }
}
