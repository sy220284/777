package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.workflow.HarnessWorkflowAcceptance
import com.labteto.dshmobile.harness.workflow.HarnessWorkflowMode
import com.labteto.dshmobile.harness.workflow.HarnessWorkflowProgress
import com.labteto.dshmobile.harness.workflow.HarnessWorkflowRunner

/** Runs read-only delegated work with bounded reassignment and explicit output checks. */
internal class LocalWorkflowCoordinator(
    private val execute: suspend (String) -> String,
    private val pruneOutput: (String) -> String,
    private val onProgress: (HarnessWorkflowProgress) -> Unit,
) {
    private val runner = HarnessWorkflowRunner(maxTasks = 4, maxParallelism = 4)

    suspend fun run(tasks: List<String>, mode: String, requiredEvidence: List<String>): String {
        val workflowMode = HarnessWorkflowMode.parse(mode)
        require(tasks.size in 1..4 && tasks.none { it.isBlank() }) { "工作流需要 1 到 4 个非空子任务" }
        require(requiredEvidence.isEmpty() || requiredEvidence.size == tasks.size) {
            "验收证据必须与子任务逐项对应"
        }
        val results = runner.run(
            tasks = tasks,
            mode = workflowMode,
            execute = { _, task, previous ->
                val prompt = if (workflowMode == HarnessWorkflowMode.PIPELINE && !previous.isNullOrBlank()) {
                    "上一步结果：\n${pruneOutput(previous)}\n\n当前阶段：\n$task"
                } else task
                execute(prompt)
            },
            accept = { index, _, output ->
                val evidence = requiredEvidence.getOrNull(index)?.trim().orEmpty()
                when {
                    output.isBlank() -> HarnessWorkflowAcceptance(false, "子任务没有产出")
                    evidence.isNotEmpty() && !output.contains(evidence, ignoreCase = true) ->
                        HarnessWorkflowAcceptance(false, "产出缺少验收证据：${evidence.take(120)}")
                    else -> HarnessWorkflowAcceptance(true)
                }
            },
            maxAttempts = 2, // Delegates are read-only; do not apply this to mutating tools.
            onProgress = onProgress,
            shouldRetryError = { error ->
                val delegated = error as? LocalSubagentExecutionException
                delegated == null || (!isTerminalRouteFailure(delegated.errorCode.orEmpty()) && delegated.retryable)
            },
        )
        return results.joinToString("\n\n") { result ->
            val label = if (workflowMode == HarnessWorkflowMode.PIPELINE) "阶段" else "子任务"
            if (result.succeeded) {
                val check = if (requiredEvidence.getOrNull(result.index).isNullOrBlank()) "基础检查：产出非空" else "指定证据已命中"
                "$label ${result.index + 1}：${result.task}\n${result.output.orEmpty()}\n$check（尝试 ${result.attempts} 次）"
            } else {
                val next = if (workflowMode == HarnessWorkflowMode.PARALLEL) "其他子任务不受影响。" else "后续阶段已停止。"
                "$label ${result.index + 1} 受阻：${result.error.orEmpty()}；仅在可恢复或验收不足时允许重新指派。$next"
            }
        }
    }
}
