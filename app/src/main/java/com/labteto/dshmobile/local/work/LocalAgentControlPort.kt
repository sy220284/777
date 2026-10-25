package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.LocalAgentDelegationKind
import com.labteto.dshmobile.local.agent.LocalAgentExecutionBackend
import com.labteto.dshmobile.local.agent.LocalSubagentHistoryMode
import com.labteto.dshmobile.local.agent.buildLocalDelegatedLaunchSpec
import com.labteto.dshmobile.local.agent.captureLocalAgentParentLink
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.model.LocalToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

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
    val executionBackend: LocalAgentExecutionBackend = LocalAgentExecutionBackend.IN_PROCESS,
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

/** Workflow is a first-class AgentControl operation, with its own durable admission. */
internal data class LocalAgentWorkflowRequest(
    val tasks: List<String>,
    val mode: String,
    val requiredEvidence: List<String>,
    val modelOverride: String?,
    val outputSchema: JsonObject?,
    val toolAllowlist: Set<String>?,
    val dependencies: List<List<Int>> = emptyList(),
    val allowMutation: Boolean = false,
    val requiredFiles: List<String> = emptyList(),
    val inputFiles: List<String> = emptyList(),
    val nodeInputFiles: List<List<String>> = emptyList(),
    val nodeOutputFiles: List<List<String>> = emptyList(),
    val resumeGraphId: String? = null,
    val replanGraphId: String? = null,
)

/**
 * Codex-style lifecycle port for Work child Agents.
 * Owner of execution facts remains the existing Job/Session/Run/Tool capabilities.
 */
internal interface LocalAgentControlPort {
    /** Single Lead-side Team admission through the existing Team owner and EventLog. */
    suspend fun executeTeam(binding: LocalWorkRunBinding, call: LocalToolCall): String?
    suspend fun delegate(binding: LocalWorkRunBinding, request: LocalAgentDispatchRequest): String
    suspend fun workflow(binding: LocalWorkRunBinding, request: LocalAgentWorkflowRequest): String
    suspend fun resumePendingWorkflows(binding: LocalWorkRunBinding)
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
    /** Bounded cancellation-aware long poll for changes to one owned Agent. */
    suspend fun watch(sessionId: String, agentId: String, afterCursor: String, maxWaitMs: Int): String
    /** Stop this Activation without deleting the resumable Agent identity/Inbox. */
    fun interrupt(sessionId: String, agentId: String): String
    /** Terminal cancellation; unlike interrupt, this clears the durable Inbox. */
    fun close(sessionId: String, agentId: String): String
    /** Request admission through the existing trusted recovery coordinator, not direct replay. */
    fun resume(sessionId: String, agentId: String): String
    /** Resolve the cold child through the exact live parent before targeted resume. */
    suspend fun ensureChildLoaded(sessionId: String, agentId: String): String
}

internal class LocalWorkAgentControlRuntime(
    private val jobs: LocalJobManager,
    private val subagents: LocalWorkSubagentRuntime,
    private val persistentJobs: LocalPersistentJobRecoveryCoordinator,
    private val teams: LocalAgentTeamRuntime,
) : LocalAgentControlPort {
    override suspend fun executeTeam(binding: LocalWorkRunBinding, call: LocalToolCall): String? =
        teams.execute(call, binding)

    private val spawns = LocalAgentSpawnController(subagents, persistentJobs)

    override suspend fun delegate(binding: LocalWorkRunBinding, request: LocalAgentDispatchRequest): String =
        spawns.delegate(binding, request)

    override suspend fun workflow(
        binding: LocalWorkRunBinding, request: LocalAgentWorkflowRequest,
    ): String = subagents.runWorkflow(
        tasks = request.tasks, mode = request.mode,
        requiredEvidence = request.requiredEvidence, modelOverride = request.modelOverride,
        outputSchema = request.outputSchema, toolAllowlist = request.toolAllowlist,
        binding = binding, dependencies = request.dependencies,
        allowMutation = request.allowMutation, requiredFiles = request.requiredFiles,
        inputFiles = request.inputFiles, nodeInputFiles = request.nodeInputFiles,
        nodeOutputFiles = request.nodeOutputFiles, resumeGraphId = request.resumeGraphId,
        replanGraphId = request.replanGraphId,
    )

    override suspend fun resumePendingWorkflows(binding: LocalWorkRunBinding) {
        subagents.resumePendingWorkflows(binding)
    }

    override fun list(sessionId: String): String = registry.list(sessionId)
    override fun tree(binding: LocalWorkRunBinding, maxNodes: Int): String =
        readLocalAgentTree(binding.eventLog, maxNodes).render()
    override fun send(
        sessionId: String,
        agentId: String,
        message: String,
        mode: LocalAgentMessageDeliveryMode,
    ): String {
        registry.resolveStandalone(sessionId, agentId)
        return persistentJobs.sendMessage(agentId, message, sessionId, mode)
    }
    private val registry = LocalAgentRegistry(jobs)
    private val lifecycle = LocalAgentLifecycleController(jobs, persistentJobs, registry)

    override fun inspect(sessionId: String, agentId: String): String =
        lifecycle.inspect(sessionId, agentId)

    override suspend fun watch(
        sessionId: String,
        agentId: String,
        afterCursor: String,
        maxWaitMs: Int,
    ): String = lifecycle.watch(sessionId, agentId, afterCursor, maxWaitMs)

    override fun interrupt(sessionId: String, agentId: String): String =
        lifecycle.interrupt(sessionId, agentId)

    override fun close(sessionId: String, agentId: String): String =
        lifecycle.close(sessionId, agentId)

    override fun resume(sessionId: String, agentId: String): String =
        lifecycle.resume(sessionId, agentId)

    override suspend fun ensureChildLoaded(sessionId: String, agentId: String): String =
        lifecycle.ensureChildLoaded(sessionId, agentId)

}