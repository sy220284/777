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
) {
    val succeeded: Boolean get() = error == null
}

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
        execute: suspend (index: Int, task: String, previousOutput: String?) -> String,
    ): List<HarnessWorkflowTaskResult> {
        val clean = tasks
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(maxTasks)
        require(clean.isNotEmpty()) { "工作流至少需要一个子任务" }
        return when (mode) {
            HarnessWorkflowMode.PARALLEL -> runParallel(clean, execute)
            HarnessWorkflowMode.PIPELINE -> runPipeline(clean, execute)
        }
    }

    private suspend fun runParallel(
        tasks: List<String>,
        execute: suspend (index: Int, task: String, previousOutput: String?) -> String,
    ): List<HarnessWorkflowTaskResult> = coroutineScope {
        val semaphore = Semaphore(maxParallelism.coerceAtMost(tasks.size))
        tasks.mapIndexed { index, task ->
            async {
                semaphore.withPermit {
                    try {
                        HarnessWorkflowTaskResult(
                            index = index,
                            task = task,
                            output = execute(index, task, null),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        HarnessWorkflowTaskResult(
                            index = index,
                            task = task,
                            error = error.message ?: error::class.java.simpleName,
                        )
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun runPipeline(
        tasks: List<String>,
        execute: suspend (index: Int, task: String, previousOutput: String?) -> String,
    ): List<HarnessWorkflowTaskResult> {
        val results = mutableListOf<HarnessWorkflowTaskResult>()
        var previous: String? = null
        for ((index, task) in tasks.withIndex()) {
            try {
                val output = execute(index, task, previous)
                results += HarnessWorkflowTaskResult(index, task, output = output)
                previous = output
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                results += HarnessWorkflowTaskResult(
                    index = index,
                    task = task,
                    error = error.message ?: error::class.java.simpleName,
                )
                break
            }
        }
        return results
    }
}
