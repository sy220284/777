package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.workflow.TaskGraphAcceptance
import com.labteto.dshmobile.harness.workflow.TaskGraphExecutor
import com.labteto.dshmobile.harness.workflow.TaskGraphNode
import com.labteto.dshmobile.harness.workflow.TaskGraphOutcome
import com.labteto.dshmobile.harness.workflow.TaskGraphProgress
import com.labteto.dshmobile.local.agent.LocalSubagentExecutionException

/**
 * Work-owned read-only delegated task workflow using the shared Kotlin DAG scheduler.
 * Work still owns acceptance policy and subagent capabilities.
 */
internal class LocalWorkflowCoordinator(
    private val execute: suspend (String) -> String,
    private val pruneOutput: (String) -> String,
    private val onProgress: (TaskGraphProgress) -> Unit,
) {
    private val graph = TaskGraphExecutor(maxNodes = 64, maxParallelism = 4)

    suspend fun run(
        tasks: List<String>,
        mode: String,
        requiredEvidence: List<String>,
        dependencies: List<List<Int>> = emptyList(),
    ): String {
        require(tasks.size in 1..64 && tasks.none(String::isBlank)) {
            "工作流需要 1 到 64 个非空子任务"
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
        val results = graph.run(
            nodes = nodes,
            retrySafe = true, // Work delegates are explicitly read-only.
            maxAttempts = 2,
            execute = { context, prompt ->
                val dependenciesText = context.dependencyOutputs.entries.joinToString("\n\n") {
                    (id, output) -> "$id 的已验收结果：\n${pruneOutput(output)}"
                }
                val actualPrompt = if (dependenciesText.isNotBlank()) {
                    "依赖任务的已验收结果：\n$dependenciesText\n\n当前任务：\n$prompt"
                } else prompt
                execute(actualPrompt)
            },
            accept = { context, output ->
                val evidence = requiredEvidence.getOrNull(context.index)?.trim().orEmpty()
                when {
                    output.isBlank() -> TaskGraphAcceptance(false, "子任务没有产出")
                    evidence.isNotEmpty() && !output.contains(evidence, ignoreCase = true) ->
                        TaskGraphAcceptance(false, "产出缺少验收证据：${evidence.take(120)}")
                    else -> TaskGraphAcceptance(true)
                }
            },
            onProgress = onProgress,
            shouldRetryError = { error ->
                val delegated = error as? LocalSubagentExecutionException
                delegated == null || (
                    delegated.retryable && !isTerminalRouteFailure(delegated.errorCode.orEmpty())
                )
            },
        )
        return results.joinToString("\n\n") { result ->
            val label = if (pipeline) "阶段" else "子任务"
            when (result.outcome) {
                TaskGraphOutcome.COMPLETED -> {
                    val check = if (requiredEvidence.getOrNull(result.index).isNullOrBlank())
                        "基础检查：产出非空" else "指定证据已命中"
                    "$label ${result.index + 1}：${result.node.task}\n${result.output.orEmpty()}\n$check（尝试 ${result.attempts} 次）"
                }
                TaskGraphOutcome.FAILED ->
                    "$label ${result.index + 1} 受阻：${result.error.orEmpty()}；尝试 ${result.attempts} 次。"
                TaskGraphOutcome.BLOCKED ->
                    "$label ${result.index + 1} 未启动：${result.error.orEmpty()}。"
            }
        }
    }
}
