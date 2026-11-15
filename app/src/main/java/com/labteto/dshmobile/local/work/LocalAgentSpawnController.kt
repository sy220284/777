package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.LocalAgentDelegationKind
import com.labteto.dshmobile.local.agent.LocalSubagentHistoryMode
import com.labteto.dshmobile.local.agent.buildLocalDelegatedLaunchSpec
import com.labteto.dshmobile.local.agent.captureLocalAgentParentLink

/** One admission contract for standalone background children and Team members. */
internal data class LocalPersistentAgentLaunchRequest(
    val task: String,
    val instructions: String,
    val model: String?,
    val maxSteps: Int,
    val parentToolCallId: String?,
    val backendCapabilities: com.labteto.dshmobile.local.agent.LocalSubagentCapabilities,
    val virtualScreen: Boolean = false,
    val outputSchema: kotlinx.serialization.json.JsonObject? = null,
    val toolAllowlist: Set<String>? = null,
    val requestedJobId: String? = null,
    val forkParentHistory: Boolean = false,
    val allowMutation: Boolean = false,
    val teamManaged: Boolean = false,
    val workspaceRepository: String? = null,
    val initialOptionalTools: Set<String> = emptySet(),
) {
    init {
        require(task.isNotBlank() && maxSteps > 0) { "AGENT_LAUNCH_REQUEST_INVALID" }
        require(!teamManaged || !requestedJobId.isNullOrBlank()) { "TEAM_MEMBER_JOB_ID_REQUIRED" }
        require(teamManaged || (!allowMutation && !forkParentHistory && workspaceRepository == null)) {
            "STANDALONE_BACKGROUND_LAUNCH_READONLY"
        }
    }
}

/**
 * Codex agent/control/spawn_guard.rs and agent/api.rs separate spawn admission from
 * mailbox delivery. Delegation remains backed by the existing Work Job and Run owners;
 * this controller exclusively handles foreground, persistent-readonly and fork launches.
 */
internal class LocalAgentSpawnController(
    private val subagents: LocalWorkSubagentRuntime,
    private val persistentJobs: LocalPersistentJobRecoveryCoordinator,
) {
    /** Both modes use the single persistent Job/Checkpoint owner. Team supplies membership only. */
    suspend fun startPersistent(
        binding: LocalWorkRunBinding,
        request: LocalPersistentAgentLaunchRequest,
    ): com.labteto.dshmobile.harness.jobs.JobStartResult = persistentJobs.startReadonlySubagentResult(
        task = request.task,
        model = request.model,
        maxSteps = request.maxSteps,
        instructions = request.instructions,
        virtualScreen = request.virtualScreen,
        outputSchema = request.outputSchema,
        toolAllowlist = request.toolAllowlist,
        forkParentCallId = request.parentToolCallId.takeIf { request.forkParentHistory },
        parentToolCallId = request.parentToolCallId,
        sessionId = binding.sessionId,
        boundState = binding.aggregateSnapshot(),
        historySnapshot = binding.runHandle.modelHistory::snapshot,
        requestedJobId = request.requestedJobId,
        backendCapabilities = request.backendCapabilities,
        allowMutation = request.allowMutation,
        teamManaged = request.teamManaged,
        workspaceRepository = request.workspaceRepository,
        initialOptionalTools = request.initialOptionalTools,
    )

    suspend fun delegate(binding: LocalWorkRunBinding, request: LocalAgentDispatchRequest): String {
        // Fail before Job creation, event recording, credential access or model admission.
        val backendCapabilities = subagents.pinBackend(request.executionBackend)
        val parent = captureLocalAgentParentLink(request.parentToolCallId)
        require(parent == null || parent.sessionId == binding.sessionId) { "AGENT_PARENT_SESSION_MISMATCH" }
        return when (request.kind) {
            LocalAgentDispatchKind.READONLY_BACKGROUND -> startPersistent(
                binding, LocalPersistentAgentLaunchRequest(
                    task = request.task,
                    instructions = request.instructions,
                    model = request.modelOverride,
                    maxSteps = request.maxSteps,
                    parentToolCallId = request.parentToolCallId,
                    virtualScreen = request.virtualScreen,
                    outputSchema = request.outputSchema,
                    toolAllowlist = request.toolAllowlist,
                    backendCapabilities = backendCapabilities,
                ),
            ).message
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
                    backendCapabilities = backendCapabilities,
                ),
            )
            LocalAgentDispatchKind.FORK -> {
                val spec = buildLocalDelegatedLaunchSpec(
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
                    backendCapabilities = backendCapabilities,
                )
                request.workspaceRepository?.let { repository ->
                    subagents.runInWorktree(binding, spec, repository)
                } ?: subagents.runner(binding).run(spec)
            }
        }
    }

}
