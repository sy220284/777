package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalSubagentRunner
import com.labteto.dshmobile.local.agent.LocalAgentRunKind
import com.labteto.dshmobile.local.agent.LocalSubagentRunnerFactory
import com.labteto.dshmobile.local.agent.requireCompletedOutput
import com.labteto.dshmobile.local.memory.LocalMemoryTools
import com.labteto.dshmobile.local.model.LocalToolCall
import kotlinx.serialization.json.JsonArray

/**
 * Work-owned subagent and workflow composition.
 *
 * Shared Agent/Tool implementations are injected as narrow capabilities. Work keeps ownership of
 * its runner binding, worker selection and workflow progress projection.
 */
internal class LocalWorkSubagentRuntime(
    private val factory: LocalSubagentRunnerFactory,
    private val schemasProvider: (Boolean, Boolean, Set<String>) -> JsonArray,
    private val executeTool: suspend (
        binding: LocalWorkRunBinding,
        call: LocalToolCall,
        allowMutation: Boolean,
        memoryTools: LocalMemoryTools,
        enabledOptionalTools: MutableSet<String>,
    ) -> AgentToolResult,
    private val pruneOutput: (String) -> String,
) {
    internal fun runner(binding: LocalWorkRunBinding): LocalSubagentRunner =
        factory.createBound(
            sessionId = binding.sessionId,
            boundState = binding.aggregateSnapshot(),
            runKind = LocalAgentRunKind.SUBAGENT,
            schemasProvider = schemasProvider,
            executeTool = { call, allowMutation, memoryTools, enabledOptional ->
                executeTool(binding, call, allowMutation, memoryTools, enabledOptional)
            },
            historySnapshot = binding.runHandle.modelHistory::snapshot,
            modelAdmission = binding.executionControl.asModelAdmissionPort(),
        )

    internal suspend fun runWorkflow(
        tasks: List<String>,
        mode: String,
        requiredEvidence: List<String>,
        modelOverride: String?,
        state: LocalWorkStatePort,
        snapshot: () -> LocalHarnessState,
        sessionId: String,
        runner: LocalSubagentRunner,
    ): String {
        val workerSelection = LocalWorkerModelRouter.resolve(modelOverride, snapshot())
        state.update { current -> current.copy(workflowProgress = null) }

        return LocalWorkflowCoordinator(
            execute = { prompt ->
                runner.runResult(
                    task = prompt,
                    inheritHistory = false,
                    allowMutation = false,
                    modelOverride = workerSelection,
                    maxSteps = snapshot().subagentMaxSteps,
                ).requireCompletedOutput()
            },
            pruneOutput = pruneOutput,
            onProgress = { progress ->
                state.update { current ->
                    val previousBlock = current.workflowProgress?.takeIf { it.needsUserAction }
                    val blocked = progress.stage == "受阻"
                    current.copy(
                        workflowProgress = LocalWorkflowProgress(
                            sessionId = sessionId,
                            stage = progress.stage,
                            task = progress.task,
                            completed = progress.completed,
                            total = progress.total,
                            blockedReason = if (blocked) progress.detail else previousBlock?.blockedReason,
                            needsUserAction = blocked || previousBlock != null,
                        ),
                    )
                }
            },
        ).run(tasks, mode, requiredEvidence)
    }
}
