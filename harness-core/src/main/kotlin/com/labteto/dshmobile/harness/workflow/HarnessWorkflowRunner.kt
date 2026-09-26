package com.labteto.dshmobile.harness.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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

data class HarnessWorkflowCheckpoint(
    val mode: HarnessWorkflowMode,
    val tasks: List<String>,
    val results: List<HarnessWorkflowTaskResult>,
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
        resume: HarnessWorkflowCheckpoint? = null,
        onCheckpoint: suspend (HarnessWorkflowCheckpoint) -> Unit = { },
        execute: suspend (index: Int, task: String, previousOutput: String?) -> String,
    ): List<HarnessWorkflowTaskResult> {
        val clean = tasks
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(maxTasks)
        require(clean.isNotEmpty()) { "工作流至少需要一个子任务" }
        val acceptedResume = resume?.also { checkpoint ->
            require(checkpoint.mode == mode) { "工作流恢复模式不匹配" }
            require(checkpoint.tasks == clean) { "工作流恢复任务列表不匹配" }
            require(checkpoint.results.all { result ->
                result.index in clean.indices && clean[result.index] == result.task
            }) { "工作流恢复检查点包含无效任务索引" }
        }
        return when (mode) {
            HarnessWorkflowMode.PARALLEL -> runParallel(clean, acceptedResume, onCheckpoint, execute)
            HarnessWorkflowMode.PIPELINE -> runPipeline(clean, acceptedResume, onCheckpoint, execute)
        }
    }

    private suspend fun runParallel(
        tasks: List<String>,
        resume: HarnessWorkflowCheckpoint?,
        onCheckpoint: suspend (HarnessWorkflowCheckpoint) -> Unit,
        execute: suspend (index: Int, task: String, previousOutput: String?) -> String,
    ): List<HarnessWorkflowTaskResult> = coroutineScope {
        val completed = resume?.results.orEmpty().associateByTo(linkedMapOf(), HarnessWorkflowTaskResult::index)
        val remaining = tasks.indices.filterNot(completed::containsKey)
        if (remaining.isEmpty()) return@coroutineScope completed.values.sortedBy(HarnessWorkflowTaskResult::index)

        val semaphore = Semaphore(maxParallelism.coerceAtMost(remaining.size))
        val checkpointLock = Mutex()
        remaining.map { index ->
            async {
                semaphore.withPermit {
                    val task = tasks[index]
                    val result = try {
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
                    checkpointLock.withLock {
                        completed[index] = result
                        onCheckpoint(
                            HarnessWorkflowCheckpoint(
                                mode = HarnessWorkflowMode.PARALLEL,
                                tasks = tasks,
                                results = completed.values.sortedBy(HarnessWorkflowTaskResult::index),
                            ),
                        )
                    }
                    result
                }
            }
        }.awaitAll()
        completed.values.sortedBy(HarnessWorkflowTaskResult::index)
    }

    private suspend fun runPipeline(
        tasks: List<String>,
        resume: HarnessWorkflowCheckpoint?,
        onCheckpoint: suspend (HarnessWorkflowCheckpoint) -> Unit,
        execute: suspend (index: Int, task: String, previousOutput: String?) -> String,
    ): List<HarnessWorkflowTaskResult> {
        val results = resume?.results.orEmpty()
            .sortedBy(HarnessWorkflowTaskResult::index)
            .toMutableList()
        require(results.indices.all { index -> results[index].index == index }) {
            "流水线恢复检查点必须连续"
        }
        if (results.any { !it.succeeded }) return results

        var previous: String? = results.lastOrNull()?.output
        for (index in results.size until tasks.size) {
            val task = tasks[index]
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
            }
            onCheckpoint(
                HarnessWorkflowCheckpoint(
                    mode = HarnessWorkflowMode.PIPELINE,
                    tasks = tasks,
                    results = results.toList(),
                ),
            )
            if (!results.last().succeeded) break
        }
        return results
    }

}
