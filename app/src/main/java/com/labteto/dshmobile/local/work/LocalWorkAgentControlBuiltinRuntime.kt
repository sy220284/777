package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalToolCall

/**
 * Compatibility adapter for older callers of the model-facing Agent built-in tools.
 * The actual control semantics are owned by [LocalAgentControlPlane].
 */
internal class LocalWorkAgentControlBuiltinRuntime(
    jobs: LocalJobManager,
    modelGateway: LocalModelGateway,
    subagents: LocalWorkSubagentRuntime,
    persistentJobs: LocalPersistentJobRecoveryCoordinator,
    teams: LocalAgentTeamRuntime,
) {
    private val control = LocalAgentControlPlane(jobs, modelGateway, subagents, persistentJobs, teams)

    internal suspend fun execute(
        call: LocalToolCall,
        allowMutation: Boolean,
        binding: LocalWorkRunBinding?,
    ): String? = control.execute(call, allowMutation, binding)

    internal companion object {
        val TOOL_NAMES: Set<String> get() = LocalAgentControlPlane.TOOL_NAMES
    }
}
