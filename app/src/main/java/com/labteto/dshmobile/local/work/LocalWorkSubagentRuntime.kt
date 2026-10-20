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
        val goalAtStart = binding.workState.snapshot().goal
        val goalIdAtStart = goalAtStart?.id.orEmpty()
        val goalFingerprintAtStart = goalAtStart?.let(::localWorkGoalFingerprint)
        fun checkGoalOwner() = requireCurrentWorkflowGoal(goalAtStart, binding.workState.snapshot().goal)
        // Preflight is pure and rejects cycles, missing nodes and capacity overflow
        // before the canonical EventLog may declare a graph started.
        val graphId = java.util.UUID.randomUUID().toString()
        // Persist a unique activation identity even if a graph ID is reused.
        val graphExecutionId = java.util.UUID.randomUUID().toString()
        val workflow = LocalWorkflowCoordinator(
            maxParallelism = binding.aggregateSnapshot().kernel.resources.maxAgents.coerceIn(1, 8),
            execute = { prompt ->
                // Fence the real subagent launch, not merely its completion projection.
                checkGoalOwner()
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
                checkGoalOwner()
                // An emitted EventLog progress fact precedes every Work UI projection.
                binding.eventLog.append("taskgraph/progress", kotlinx.serialization.json.buildJsonObject {
                    put("graph_id", graphId)
                    put("graph_execution_id", graphExecutionId)
                    put("goal_id", goalIdAtStart)
                    put("task_index", progress.index)
                    put("node_id", progress.nodeId)
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
        )
        val prepared = workflow.prepare(tasks, mode, requiredEvidence, dependencies)
        checkGoalOwner()
        binding.eventLog.append("taskgraph/start", kotlinx.serialization.json.buildJsonObject {
            put("graph_id", graphId)
            put("graph_execution_id", graphExecutionId)
            put("session_id", binding.sessionId)
            put("goal_id", goalIdAtStart)
            put("mode", mode)
            put("task_count", tasks.size)
            goalFingerprintAtStart?.let { put("goal_fingerprint", it) }
            put("nodes", kotlinx.serialization.json.buildJsonArray {
                prepared.nodes.forEach { node ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("node_id", node.id)
                        put("depends_on", kotlinx.serialization.json.buildJsonArray {
                            node.dependsOn.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        })
                    })
                }
            })
        })
        binding.workState.update { current -> current.copy(workflowProgress = null) }

        fun settleGraph(
            outcome: String,
            detail: String = "",
            result: LocalWorkflowRunResult? = null,
        ) {
            binding.eventLog.append("taskgraph/end", kotlinx.serialization.json.buildJsonObject {
                put("graph_id", graphId)
                put("graph_execution_id", graphExecutionId)
                put("goal_id", goalIdAtStart)
                put("outcome", outcome)
                goalFingerprintAtStart?.let { put("goal_fingerprint", it) }
                put("detail", detail.take(500))
                result?.let {
                    put("total_nodes", it.total)
                    put("completed_nodes", it.completed)
                    put("failed_nodes", it.failed)
                    put("blocked_nodes", it.blocked)
                }
            })
        }
        try {
            val result = workflow.runDetailed(prepared)
            // Graph execution outcome is typed and persistent. Even if every
            // node succeeds, Work's Goal Verifier separately decides delivery.
            settleGraph(result.executionOutcome, result = result)
            return result.summary
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try { settleGraph("cancelled") }
                catch (logFailure: Exception) { cancelled.addSuppressed(logFailure) }
            }
            throw cancelled
        } catch (failure: Exception) {
            try { settleGraph("failed", failure.message.orEmpty()) }
            catch (logFailure: Exception) { failure.addSuppressed(logFailure) }
            throw failure
        }
    }
}
