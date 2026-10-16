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

    suspend fun run(tasks: List<String>, mode: String, requiredEvidence: List<String>): String {
        require(tasks.size in 1..64 && tasks.none(String::isBlank)) {
            "工作流需要 1 到 64 个非空子任务"
        }
        require(requiredEvidence.isEmpty() || requiredEvidence.size == tasks.size) {
            "验收证据必须与子任务逐项对应"
        }
        val pipeline = when (mode.lowercase()) {
            "parallel" -> false
            "pipeline" -> true
            else -> error("工作流模式必须为 parallel 或 pipeline")
        }
        val nodes = tasks.mapIndexed { index, task ->
            TaskGraphNode(
                id = "task-${index + 1}",
                task = task,
                dependsOn = if (pipeline && index > 0) listOf("task-$index") else emptyList(),
            )
        }
        val results = graph.run(
            nodes = nodes,
            retrySafe = true, // Work delegates are explicitly read-only.
            maxAttempts = 2,
            execute = { context, prompt ->
                val prior = if (pipeline) context.dependencyOutputs.values.lastOrNull() else null
                val actualPrompt = if (!prior.isNullOrBlank()) {
                    "上一步结果：\n${pruneOutput(prior)}\n\n当前阶段：\n$prompt"
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
