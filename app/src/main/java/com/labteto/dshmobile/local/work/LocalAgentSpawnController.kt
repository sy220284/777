package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.LocalAgentDelegationKind
import com.labteto.dshmobile.local.agent.requireExecutableAgentBackend
import com.labteto.dshmobile.local.agent.LocalSubagentHistoryMode
import com.labteto.dshmobile.local.agent.buildLocalDelegatedLaunchSpec
import com.labteto.dshmobile.local.agent.captureLocalAgentParentLink

/**
 * Codex agent/control/spawn_guard.rs and agent/api.rs separate spawn admission from
 * mailbox delivery. Delegation remains backed by the existing Work Job and Run owners;
 * this controller exclusively handles foreground, persistent-readonly and fork launches.
 */
internal class LocalAgentSpawnController(
    private val subagents: LocalWorkSubagentRuntime,
    private val persistentJobs: LocalPersistentJobRecoveryCoordinator,
) {
    suspend fun delegate(binding: LocalWorkRunBinding, request: LocalAgentDispatchRequest): String {
        // Fail before Job creation, event recording, credential access or model admission.
        requireExecutableAgentBackend(request.executionBackend)
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
                )
                request.workspaceRepository?.let { repository ->
                    subagents.runInWorktree(binding, spec, repository)
                } ?: subagents.runner(binding).run(spec)
            }
        }
    }

}
