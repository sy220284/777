package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.local.agent.LocalSubagentCapabilities
import com.labteto.dshmobile.local.agent.LocalSubagentHistoryMode
import com.labteto.dshmobile.local.agent.LocalSubagentLaunchSpec
import com.labteto.dshmobile.local.agent.requireCompletedOutput
import com.labteto.dshmobile.local.memory.LocalMemoryTools
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
import com.labteto.dshmobile.local.tools.LocalToolCapabilityScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** Work-owned subagent and workflow composition bound to the originating Work run. */
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
    private val pruneOutput: (LocalWorkRunBinding, String) -> String,
) {
    internal fun runner(binding: LocalWorkRunBinding): LocalSubagentRunner =
        factory.createBound(
            sessionId = binding.sessionId,
            boundState = binding.aggregateSnapshot(),
            runKind = LocalAgentRunKind.SUBAGENT,
            schemasProvider = schemasProvider,
            executeTool = { call, allowMutation, memoryTools, enabledOptional ->
                withContext(LocalToolCapabilityScope(enabledOptional)) {
                    executeTool(binding, call, allowMutation, memoryTools, enabledOptional)
                }
            },
            historySnapshot = binding.runHandle.modelHistory::snapshot,
            modelAdmission = binding.executionControl.asModelAdmissionPort(),
        )

    internal suspend fun runWorkflow(
        tasks: List<String>,
        mode: String,
        requiredEvidence: List<String>,
        modelOverride: String?,
        outputSchema: JsonObject?,
        toolAllowlist: Set<String>?,
        binding: LocalWorkRunBinding,
        runner: LocalSubagentRunner,
        dependencies: List<List<Int>> = emptyList(),
    ): String {
        val workerSelection = LocalWorkerModelRouter.resolve(modelOverride, binding.aggregateSnapshot())
        val graphId = java.util.UUID.randomUUID().toString()
        binding.eventLog.append("taskgraph/start", kotlinx.serialization.json.buildJsonObject {
            put("graph_id", graphId)
            put("session_id", binding.sessionId)
            put("goal_id", binding.workState.snapshot().goal?.id ?: "")
            put("mode", mode)
            put("task_count", tasks.size)
        })
        binding.workState.update { current -> current.copy(workflowProgress = null) }

        return LocalWorkflowCoordinator(
            execute = { prompt ->
                runner.runResult(
                    LocalSubagentLaunchSpec(
                        task = prompt,
                        modelOverride = workerSelection,
                        maxSteps = binding.aggregateSnapshot().subagentMaxSteps,
                        capabilities = LocalSubagentCapabilities(
                            allowMutation = false,
                            continuable = false,
                            virtualScreen = false,
                            historyMode = LocalSubagentHistoryMode.ISOLATED,
                            toolAllowlist = toolAllowlist,
                            outputSchema = outputSchema,
                        ),
                    ),
                ).requireCompletedOutput()
            },
            pruneOutput = { value -> pruneOutput(binding, value) },
            onProgress = { progress ->
                // The canonical EventLog settles each task transition before UI projection.
                binding.eventLog.append("taskgraph/progress", kotlinx.serialization.json.buildJsonObject {
                    put("graph_id", graphId)
                    put("goal_id", binding.workState.snapshot().goal?.id ?: "")
                    put("task_index", progress.index)
                    put("task", progress.task.take(500))
                    put("stage", progress.stage)
                    put("completed", progress.completed)
                    put("total", progress.total)
                    put("detail", progress.detail.take(500))
                })
                binding.workState.update { current ->
                    val previousBlock = current.workflowProgress?.takeIf { it.needsUserAction }
                    val blocked = progress.stage == "受阻"
                    current.copy(
                        workflowProgress = LocalWorkflowProgress(
                            sessionId = binding.sessionId,
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
        ).run(tasks, mode, requiredEvidence, dependencies)
    }
}
