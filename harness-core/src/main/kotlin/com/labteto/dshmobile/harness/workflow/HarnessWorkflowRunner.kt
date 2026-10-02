package com.labteto.dshmobile.harness.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class HarnessWorkflowMode {
    PARALLEL,
    PIPELINE;

    companion object {
        fun parse(value: String): HarnessWorkflowMode = when (value.lowercase()) {
            "parallel" -> PARALLEL
            "pipeline" -> PIPELINE
            else -> error("工作流模式必须为 parallel 或 pipeline")
        }
    }
}

data class HarnessWorkflowTaskResult(
    val index: Int,
    val task: String,
    val output: String? = null,
    val error: String? = null,
    val attempts: Int = 1,
) {
    val succeeded: Boolean get() = error == null
}

data class HarnessWorkflowAcceptance(val accepted: Boolean, val feedback: String = "")

data class HarnessWorkflowProgress(
    val index: Int,
    val task: String,
    val stage: String,
    val completed: Int,
    val total: Int,
    val detail: String = "",
)

/**
 * Platform-neutral workflow coordinator.
 *
 * Parallel branches are failure-isolated and preserve input ordering. Pipeline stages pass the
 * previous successful output forward and stop after the first thrown stage error so later stages
 * cannot run on an invalid predecessor.
 */
class HarnessWorkflowRunner(
    private val maxTasks: Int = 4,
    private val maxParallelism: Int = 4,
) {
    init {
        require(maxTasks in 1..32) { "工作流任务上限必须在 1..32 之间" }
        require(maxParallelism in 1..32) { "工作流并行度必须在 1..32 之间" }
    }

    suspend fun run(
        tasks: List<String>,
        mode: HarnessWorkflowMode,
        accept: suspend (index: Int, task: String, output: String) -> HarnessWorkflowAcceptance =
            { _, _, output -> HarnessWorkflowAcceptance(output.isNotBlank(), "子任务没有产出") },
        maxAttempts: Int = 1,
        onProgress: (HarnessWorkflowProgress) -> Unit = {},
        shouldRetryError: (Exception) -> Boolean = { true },
        execute: suspend (index: Int, task: String, previousOutput: String?) -> String,
    ): List<HarnessWorkflowTaskResult> {
        require(maxAttempts in 1..3) { "工作流尝试次数必须在 1..3 之间" }
        val clean = tasks
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(maxTasks)
        require(clean.isNotEmpty()) { "工作流至少需要一个子任务" }
        return when (mode) {
            HarnessWorkflowMode.PARALLEL -> runParallel(clean, execute, accept, maxAttempts, onProgress, shouldRetryError)
            HarnessWorkflowMode.PIPELINE -> runPipeline(clean, execute, accept, maxAttempts, onProgress, shouldRetryError)
        }
    }

    private suspend fun runTask(
        index: Int,
        task: String,
        previous: String?,
        total: Int,
        completed: () -> Int,
        execute: suspend (Int, String, String?) -> String,
        accept: suspend (Int, String, String) -> HarnessWorkflowAcceptance,
        maxAttempts: Int,
        onProgress: (HarnessWorkflowProgress) -> Unit,
        shouldRetryError: (Exception) -> Boolean,
    ): HarnessWorkflowTaskResult {
        var feedback = ""
        for (attempt in 1..maxAttempts) {
            onProgress(HarnessWorkflowProgress(index, task, if (attempt == 1) "执行中" else "重新指派", completed(), total, feedback))
            try {
                // Only read-only/idempotent executors should opt in to a second attempt.
                val prompt = if (attempt == 1) task else "$task\n\n上次失败或验收未通过：$feedback。请核查并补足证据。"
                val output = execute(index, prompt, previous)
                onProgress(HarnessWorkflowProgress(index, task, "验收中", completed(), total))
                val verdict = accept(index, task, output)
                if (verdict.accepted) {
                    return HarnessWorkflowTaskResult(index, task, output = output, attempts = attempt)
                }
                feedback = verdict.feedback.ifBlank { "验收未通过" }.take(500)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!shouldRetryError(error)) throw error
                feedback = (error.message ?: error::class.java.simpleName).take(500)
            }
        }
        return HarnessWorkflowTaskResult(index, task, error = feedback, attempts = maxAttempts)
    }

    private suspend fun runParallel(
        tasks: List<String>,
        execute: suspend (index: Int, task: String, previousOutput: String?) -> String,
        accept: suspend (Int, String, String) -> HarnessWorkflowAcceptance,
        maxAttempts: Int,
        onProgress: (HarnessWorkflowProgress) -> Unit,
        shouldRetryError: (Exception) -> Boolean,
    ): List<HarnessWorkflowTaskResult> = coroutineScope {
        val semaphore = Semaphore(maxParallelism.coerceAtMost(tasks.size))
        val progressLock = Any()
        var completed = 0
        tasks.mapIndexed { index, task ->
            async {
                semaphore.withPermit {
                    val result = runTask(index, task, null, tasks.size, { synchronized(progressLock) { completed } }, execute, accept, maxAttempts, onProgress, shouldRetryError)
                    synchronized(progressLock) {
                        completed++
                        onProgress(HarnessWorkflowProgress(index, task, if (result.succeeded) "已完成" else "受阻", completed, tasks.size, result.error.orEmpty()))
                    }
                    result
                }
            }
        }.awaitAll()
    }

    private suspend fun runPipeline(
        tasks: List<String>,
        execute: suspend (index: Int, task: String, previousOutput: String?) -> String,
        accept: suspend (Int, String, String) -> HarnessWorkflowAcceptance,
        maxAttempts: Int,
        onProgress: (HarnessWorkflowProgress) -> Unit,
        shouldRetryError: (Exception) -> Boolean,
    ): List<HarnessWorkflowTaskResult> {
        val results = mutableListOf<HarnessWorkflowTaskResult>()
        var previous: String? = null
        for ((index, task) in tasks.withIndex()) {
            val result = runTask(index, task, previous, tasks.size, { results.size }, execute, accept, maxAttempts, onProgress, shouldRetryError)
            results += result
            onProgress(HarnessWorkflowProgress(index, task, if (result.succeeded) "已完成" else "受阻", results.size, tasks.size, result.error.orEmpty()))
            if (!result.succeeded) break
            previous = result.output
        }
        return results
    }
}
