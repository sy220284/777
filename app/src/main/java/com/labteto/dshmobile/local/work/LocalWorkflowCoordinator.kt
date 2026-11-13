package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.workflow.TaskGraphAcceptance
import com.labteto.dshmobile.harness.workflow.TaskGraphExecutor
import com.labteto.dshmobile.harness.workflow.TaskGraphNode
import com.labteto.dshmobile.harness.workflow.TaskGraphOutcome
import com.labteto.dshmobile.harness.workflow.TaskGraphProgress
import com.labteto.dshmobile.local.agent.LocalSubagentExecutionException

internal fun workflowOutputRejection(output: String, requiredEvidence: String): String? = when {
    output.isBlank() -> "子任务没有产出"
    requiredEvidence.trim().isNotEmpty() && !output.contains(requiredEvidence.trim(), ignoreCase = true) ->
        "产出缺少验收证据：${requiredEvidence.trim().take(120)}"
    else -> null
}

/**
 * Work's current Goal is an immutable execution fence for one delegated graph.
 * A changed acceptance contract, a pause, or a newly created Goal invalidates
 * the previous work. This is a terminal policy failure, never a model retry.
 */
internal class LocalWorkflowGoalSuperseded : IllegalStateException(
    "Work Goal 已发生变化或暂停，本次旧任务图已停止，请根据当前目标重新规划",
)

internal fun requireCurrentWorkflowGoal(expected: LocalGoal?, current: LocalGoal?) {
    val same = if (expected == null) current == null else
        current != null && current.status == "active" &&
            localWorkGoalFingerprint(expected) == localWorkGoalFingerprint(current)
    if (!same) throw LocalWorkflowGoalSuperseded()
}

/** Validated immutable Work execution request; safe to persist as started. */
internal data class LocalPreparedWorkflow(
    val nodes: List<TaskGraphNode>,
    val evidence: List<String>,
    val pipeline: Boolean,
)


/**
 * Work graph execution result, independently of parent Goal completion.
 * The complete node set is retained even when one branch failed or was blocked.
 */
internal data class LocalWorkflowRunResult(
    val nodes: List<com.labteto.dshmobile.harness.workflow.TaskGraphNodeResult>,
    val summary: String,
) {
    val total: Int get() = nodes.size
    val completed: Int get() = nodes.count { it.outcome == TaskGraphOutcome.COMPLETED }
    val failed: Int get() = nodes.count { it.outcome == TaskGraphOutcome.FAILED }
    val blocked: Int get() = nodes.count { it.outcome == TaskGraphOutcome.BLOCKED }
    val allNodesCompleted: Boolean get() = total > 0 && completed == total
    val executionOutcome: String get() = when {
        allNodesCompleted -> "nodes_completed"
        completed > 0 -> "partial"
        else -> "blocked"
    }
}

/**
 * Work-owned delegated task workflow using the shared Kotlin DAG scheduler.
 * Work still owns acceptance policy and subagent capabilities.
 */
internal class LocalWorkflowCoordinator(
    private val execute: suspend (String) -> String,
    private val pruneOutput: (String) -> String,
    private val onProgress: (TaskGraphProgress) -> Unit,
    maxParallelism: Int = 4,
    private val executeNode: (suspend (com.labteto.dshmobile.harness.workflow.TaskGraphTaskContext, String) -> String)? = null,
    private val acceptNode: suspend (com.labteto.dshmobile.harness.workflow.TaskGraphTaskContext, String) -> TaskGraphAcceptance =
        { _, _ -> TaskGraphAcceptance(true) },
    private val onResult: (com.labteto.dshmobile.harness.workflow.TaskGraphNodeResult, Map<String, String>) -> Unit = { _, _ -> },
    private val retrySafe: Boolean = true,
    private val onTerminal: (com.labteto.dshmobile.harness.workflow.TaskGraphNodeResult) -> Unit = {},
) {
    private val graph = TaskGraphExecutor(maxNodes = 128, maxParallelism = maxParallelism.coerceIn(1, 8))

    suspend fun run(
        tasks: List<String>,
        mode: String,
        requiredEvidence: List<String>,
        dependencies: List<List<Int>> = emptyList(),
    ): String = run(prepare(tasks, mode, requiredEvidence, dependencies))

    fun prepare(
        tasks: List<String>,
        mode: String,
        requiredEvidence: List<String>,
        dependencies: List<List<Int>> = emptyList(),
    ): LocalPreparedWorkflow {
        require(tasks.size in 1..128 && tasks.none(String::isBlank)) {
            "工作流需要 1 到 128 个非空子任务"
        }
        require(requiredEvidence.isEmpty() || requiredEvidence.size == tasks.size) {
            "验收证据必须与子任务逐项对应"
        }
        val graphMode = mode.lowercase()
        require(graphMode in setOf("parallel", "pipeline", "graph")) {
            "工作流模式必须为 parallel、pipeline 或 graph"
        }
        require(graphMode == "graph" || dependencies.isEmpty()) {
            "显式任务依赖必须使用 graph 模式"
        }
        require(graphMode != "graph" || dependencies.size == tasks.size) {
            "graph 模式 dependencies 必须和 tasks 一一对应"
        }
        val pipeline = graphMode == "pipeline"
        val nodes = tasks.mapIndexed { index, task ->
            val required = when (graphMode) {
                "pipeline" -> if (index > 0) listOf(index - 1) else emptyList()
                "graph" -> dependencies[index]
                else -> emptyList()
            }
            require(required.distinct().size == required.size &&
                required.all { it in tasks.indices && it != index }) {
                "任务 ${index + 1} 含重复、越界或自依赖"
            }
            TaskGraphNode(
                id = "task-${index + 1}",
                task = task,
                dependsOn = required.map { dep -> "task-${dep + 1}" },
            )
        }
        graph.validateGraph(nodes)
        return LocalPreparedWorkflow(nodes, requiredEvidence.toList(), pipeline)
    }

    suspend fun run(plan: LocalPreparedWorkflow): String =
        runDetailed(plan).summary

    suspend fun runDetailed(
        plan: LocalPreparedWorkflow,
        initialResults: Map<String, com.labteto.dshmobile.harness.workflow.TaskGraphNodeResult> = emptyMap(),
    ): LocalWorkflowRunResult {
        val pipeline = plan.pipeline
        val requiredEvidence = plan.evidence
        val results = graph.run(
            nodes = plan.nodes,
            initialResults = initialResults,
            onResult = onResult,
            onTerminal = onTerminal,
            retrySafe = retrySafe,
            maxAttempts = if (retrySafe) 2 else 1,
            execute = { context, prompt ->
                val dependenciesText = context.dependencyOutputs.entries.joinToString("\n\n") {
                    (id, output) -> "$id 的已验收结果：\n${pruneOutput(output)}"
                }
                val actualPrompt = if (dependenciesText.isNotBlank()) {
                    "依赖任务的已验收结果：\n$dependenciesText\n\n当前任务：\n$prompt"
                } else prompt
                executeNode?.invoke(context, actualPrompt) ?: execute(actualPrompt)
            },
            accept = { context, output ->
                val evidence = requiredEvidence.getOrNull(context.index)?.trim().orEmpty()
                workflowOutputRejection(output, evidence)?.let { TaskGraphAcceptance(false, it) }
                    ?: acceptNode(context, output)
            },
            onProgress = onProgress,
            shouldRetryError = { error ->
                val delegated = error as? LocalSubagentExecutionException
                error !is LocalWorkflowGoalSuperseded && (delegated == null || (
                    delegated.retryable && !isTerminalRouteFailure(delegated.errorCode.orEmpty())
                ))
            },
        )
        val summary = results.joinToString("\n\n") { result ->
            val label = if (pipeline) "阶段" else "子任务"
            when (result.outcome) {
                TaskGraphOutcome.COMPLETED -> {
                    val check = if (requiredEvidence.getOrNull(result.index).isNullOrBlank())
                        "基础检查：产出非空" else "指定证据已命中"
                    "$label ${result.index + 1}：${result.node.task}\n${result.output.orEmpty()}\n$check（尝试 ${result.attempts} 次）"
                }
                TaskGraphOutcome.FAILED ->
                    "$label ${result.index + 1}：${result.node.task}\n受阻：${result.error.orEmpty()}；尝试 ${result.attempts} 次。"
                TaskGraphOutcome.BLOCKED ->
                    "$label ${result.index + 1}：${result.node.task}\n未启动：${result.error.orEmpty()}。"
            }
        }
        val projection = LocalWorkflowRunResult(results, summary)
        // A node failure must be visible to the next agent turn, never rendered
        // as an entirely successful graph solely because execution returned.
        return if (projection.allNodesCompleted) projection else projection.copy(
            summary = "工作流仍有未完成节点：已完成 ${projection.completed}/${projection.total}，" +
                "失败 ${projection.failed}，因依赖受阻 ${projection.blocked}。\n\n" + summary,
        )
    }
}
