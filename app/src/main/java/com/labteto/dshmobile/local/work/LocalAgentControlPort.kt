package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.LocalAgentDelegationKind
import com.labteto.dshmobile.local.agent.LocalSubagentHistoryMode
import com.labteto.dshmobile.local.agent.buildLocalDelegatedLaunchSpec
import com.labteto.dshmobile.local.agent.captureLocalAgentParentLink
import com.labteto.dshmobile.local.jobs.LocalJobManager
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
    fun interrupt(sessionId: String, agentId: String): String
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
    override fun interrupt(sessionId: String, agentId: String): String = jobs.kill(agentId, sessionId)
}
