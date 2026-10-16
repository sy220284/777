package com.labteto.dshmobile.harness.workflow

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * A task belongs to one immutable graph. Dependencies refer to node IDs, not
 * mutable list positions. Task result snapshots are returned in declaration order.
 */
data class TaskGraphNode(
    val id: String,
    val task: String,
    val dependsOn: List<String> = emptyList(),
)

enum class TaskGraphOutcome { COMPLETED, FAILED, BLOCKED }

data class TaskGraphNodeResult(
    val node: TaskGraphNode,
    val index: Int,
    val outcome: TaskGraphOutcome,
    val output: String? = null,
    val error: String? = null,
    val attempts: Int = 0,
) {
    val succeeded: Boolean get() = outcome == TaskGraphOutcome.COMPLETED
}

data class TaskGraphAcceptance(val accepted: Boolean, val feedback: String = "")

data class TaskGraphProgress(
    val index: Int,
    val task: String,
    val stage: String,
    val completed: Int,
    val total: Int,
    val detail: String = "",
)

data class TaskGraphTaskContext(
    val index: Int,
    val node: TaskGraphNode,
    val dependencyOutputs: Map<String, String>,
)

/**
 * Platform-neutral DAG scheduler used by Work's read-only subagent delegation.
 * Its authority is confined to task admission and dependency ordering. EventLog,
 * persisted Agent Team facts, tool approval and resource leases keep their owners.
 *
 * No implicit truncation, no unbounded fan-out, no automatic mutation replay.
 * Nodes blocked by a failed predecessor remain visible as BLOCKED results.
 */
class TaskGraphExecutor(
    private val maxNodes: Int = 64,
    private val maxParallelism: Int = 4,
) {
    init {
        require(maxNodes in 1..256) { "Task graph capacity must be in 1..256" }
        require(maxParallelism in 1..32) { "Task graph parallelism must be in 1..32" }
    }

    suspend fun run(
        nodes: List<TaskGraphNode>,
        maxAttempts: Int = 1,
        retrySafe: Boolean = false,
        accept: suspend (TaskGraphTaskContext, String) -> TaskGraphAcceptance =
            { _, output -> TaskGraphAcceptance(output.isNotBlank(), "子任务没有产出") },
        shouldRetryError: (Exception) -> Boolean = { true },
        onProgress: (TaskGraphProgress) -> Unit = {},
        execute: suspend (TaskGraphTaskContext, String) -> String,
    ): List<TaskGraphNodeResult> = coroutineScope {
        val accepted = validateGraph(nodes)
        require(maxAttempts in 1..3) { "Task graph retries must be in 1..3" }
        require(maxAttempts == 1 || retrySafe) {
            "Only proven read-only/idempotent graph executors may retry"
        }
        val semaphore = Semaphore(maxParallelism)
        val completed = AtomicInteger(0)
        val results = LinkedHashMap<String, TaskGraphNodeResult>()
        val indices = accepted.mapIndexed { i, node -> node.id to i }.toMap()
        val total = accepted.size

        // Each wave runs only nodes whose dependencies are already settled.
        // Branch failure stops descendants while independent branches proceed.
        while (results.size < total) {
            // Propagate a failed dependency through the *entire* DAG before choosing
            // ready nodes. A single pass leaves grandchildren incorrectly "waiting"
            // and can cause false deadlock when the root branch has already failed.
            while (true) {
                val blocked = accepted.filter { node ->
                    node.id !in results && node.dependsOn.any { dep ->
                        results[dep]?.outcome == TaskGraphOutcome.FAILED ||
                            results[dep]?.outcome == TaskGraphOutcome.BLOCKED
                    }
                }
                if (blocked.isEmpty()) break
                for (node in blocked) {
                    val index = indices.getValue(node.id)
                    val failedDeps = node.dependsOn.filter {
                        results[it]?.outcome != TaskGraphOutcome.COMPLETED
                    }
                    val reason = "依赖未完成：${failedDeps.joinToString()}"
                    results[node.id] = TaskGraphNodeResult(
                        node, index, TaskGraphOutcome.BLOCKED, error = reason,
                    )
                    onProgress(TaskGraphProgress(
                        index, node.task, "受阻", completed.incrementAndGet(), total, reason,
                    ))
                }
            }
            val ready = accepted.filter { node ->
                node.id !in results && node.dependsOn.all {
                    results[it]?.outcome == TaskGraphOutcome.COMPLETED
                }
            }
            check(ready.isNotEmpty() || results.size == total) {
                "Graph cannot advance despite acyclic admission"
            }
            val finished = ready.map { node ->
                async {
                    semaphore.withPermit {
                        val ctx = TaskGraphTaskContext(
                            index = indices.getValue(node.id),
                            node = node,
                            dependencyOutputs = node.dependsOn.associateWith { dep ->
                                checkNotNull(results[dep]?.output) { "Successful dependency lost output" }
                            },
                        )
                        executeNode(
                            ctx = ctx,
                            total = total,
                            completed = completed,
                            maxAttempts = maxAttempts,
                            accept = accept,
                            execute = execute,
                            retrySafe = retrySafe,
                            shouldRetryError = shouldRetryError,
                            onProgress = onProgress,
                        )
                    }
                }
            }.awaitAll()
            for (result in finished) results[result.node.id] = result
        }
        accepted.map { node -> results.getValue(node.id) }
    }

    private suspend fun executeNode(
        ctx: TaskGraphTaskContext,
        total: Int,
        completed: AtomicInteger,
        maxAttempts: Int,
        accept: suspend (TaskGraphTaskContext, String) -> TaskGraphAcceptance,
        execute: suspend (TaskGraphTaskContext, String) -> String,
        retrySafe: Boolean,
        shouldRetryError: (Exception) -> Boolean,
        onProgress: (TaskGraphProgress) -> Unit,
    ): TaskGraphNodeResult {
        var feedback = ""
        for (attempt in 1..maxAttempts) {
            onProgress(TaskGraphProgress(
                ctx.index, ctx.node.task,
                if (attempt == 1) "执行中" else "重新指派",
                completed.get(), total, feedback,
            ))
            try {
                val prompt = if (attempt == 1) ctx.node.task
                    else "${ctx.node.task}\n\n上次失败或验收未通过：$feedback。请核查并补足证据。"
                val output = execute(ctx, prompt)
                onProgress(TaskGraphProgress(ctx.index, ctx.node.task, "验收中", completed.get(), total))
                val verdict = accept(ctx, output)
                if (verdict.accepted) {
                    val count = completed.incrementAndGet()
                    onProgress(TaskGraphProgress(ctx.index, ctx.node.task, "已完成", count, total))
                    return TaskGraphNodeResult(ctx.node, ctx.index, TaskGraphOutcome.COMPLETED,
                        output = output, attempts = attempt)
                }
                feedback = verdict.feedback.ifBlank { "验收未通过" }.take(500)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Terminal errors (including unknown side effects) must escape immediately.
                if (!retrySafe || !shouldRetryError(error)) throw error
                feedback = (error.message ?: error::class.java.simpleName).take(500)
            }
        }
        val count = completed.incrementAndGet()
        onProgress(TaskGraphProgress(ctx.index, ctx.node.task, "受阻", count, total, feedback))
        return TaskGraphNodeResult(ctx.node, ctx.index, TaskGraphOutcome.FAILED,
            error = feedback, attempts = maxAttempts)
    }

    private fun validateGraph(nodes: List<TaskGraphNode>): List<TaskGraphNode> {
        require(nodes.isNotEmpty() && nodes.size <= maxNodes) {
            "Task graph contains ${nodes.size} nodes; capacity is 1..$maxNodes"
        }
        require(nodes.all { it.id.isNotBlank() && it.task.isNotBlank() }) {
            "Task graph nodes need nonblank IDs and tasks"
        }
        val unique = nodes.associateBy { it.id }
        require(unique.size == nodes.size) { "Duplicate task graph node IDs" }
        require(nodes.all { it.dependsOn.distinct().size == it.dependsOn.size &&
            it.dependsOn.all { dep -> dep != it.id && dep in unique } }) {
            "Task graph contains duplicate, missing or self dependencies"
        }
        val visiting = HashSet<String>()
        val visited = HashSet<String>()
        fun visit(id: String) {
            if (id in visited) return
            require(visiting.add(id)) { "Task graph contains a dependency cycle" }
            unique.getValue(id).dependsOn.forEach(::visit)
            visiting.remove(id)
            visited.add(id)
        }
        nodes.forEach { visit(it.id) }
        return nodes
    }
}
