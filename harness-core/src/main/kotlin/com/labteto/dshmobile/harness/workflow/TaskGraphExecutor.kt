package com.labteto.dshmobile.harness.workflow

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
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

class TaskGraphNodeExecutionException(message: String) : IllegalStateException(message)

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
    val nodeId: String = "",
)

data class TaskGraphTaskContext(
    val index: Int,
    val node: TaskGraphNode,
    val dependencyOutputs: Map<String, String>,
)

/**
 * Platform-neutral DAG scheduler used by Work's durable subagent delegation.
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
        initialResults: Map<String, TaskGraphNodeResult> = emptyMap(),
        onResult: (TaskGraphNodeResult, Map<String, String>) -> Unit = { _, _ -> },
        onTerminal: (TaskGraphNodeResult) -> Unit = {},
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
        require(initialResults.all { (id, result) ->
            val node = accepted.singleOrNull { it.id == id }
            node == result.node && result.index == accepted.indexOf(node) &&
                result.succeeded && !result.output.isNullOrBlank() &&
                result.node.dependsOn.all { it in initialResults }
        }) { "Recovered results must be completed, match the immutable graph and include dependencies" }
        val semaphore = Semaphore(maxParallelism)
        val completed = AtomicInteger(0)
        val indices = accepted.mapIndexed { i, node -> node.id to i }.toMap()
        val total = accepted.size

        // Build all bounded lazy continuations before launching any of them. Each node
        // waits only on its own prerequisites: an unrelated slow branch no longer holds
        // back dependents of a fast branch (the previous wave/barrier behavior).
        //
        // No parallel task mutates this lookup after scheduling starts; results remain
        // immutable, and the public return order stays equal to the submitted graph.
        val scheduled = LinkedHashMap<String, Deferred<TaskGraphNodeResult>>(total)
        for (node in accepted) {
            scheduled[node.id] = async(start = CoroutineStart.LAZY) {
                val prerequisites = node.dependsOn.map { dependencyId ->
                    scheduled.getValue(dependencyId).await()
                }
                val blockedBy = node.dependsOn.zip(prerequisites)
                    .filter { (_, result) -> result.outcome != TaskGraphOutcome.COMPLETED }
                    .map { (dependencyId, _) -> dependencyId }
                val index = indices.getValue(node.id)
                val recovered = initialResults[node.id]
                val terminal = if (recovered != null) {
                    completed.incrementAndGet()
                    recovered
                } else if (blockedBy.isNotEmpty()) {
                    val reason = "依赖未完成：${blockedBy.joinToString()}"
                    val result = TaskGraphNodeResult(
                        node = node, index = index,
                        outcome = TaskGraphOutcome.BLOCKED, error = reason,
                    )
                    onProgress(TaskGraphProgress(
                        index, node.task, "受阻", completed.incrementAndGet(), total,
                        reason, nodeId = node.id,
                    ))
                    result
                } else {
                    val context = TaskGraphTaskContext(
                        index = index,
                        node = node,
                        dependencyOutputs = node.dependsOn.zip(prerequisites)
                            .associate { (dependencyId, result) ->
                                dependencyId to checkNotNull(result.output) {
                                    "Successful prerequisite lost its result"
                                }
                            },
                    )
                    semaphore.withPermit {
                        executeNode(
                            ctx = context,
                            total = total,
                            completed = completed,
                            maxAttempts = maxAttempts,
                            accept = accept,
                            execute = execute,
                            retrySafe = retrySafe,
                            shouldRetryError = shouldRetryError,
                            onProgress = onProgress,
                            onResult = onResult,
                        )
                    }
                }
                onTerminal(terminal)
                terminal
            }
        }
        // Graph admission is atomic; no model/tool executor starts during validation.
        scheduled.values.forEach { it.start() }
        accepted.map { node -> scheduled.getValue(node.id) }.awaitAll()
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
        onResult: (TaskGraphNodeResult, Map<String, String>) -> Unit,
    ): TaskGraphNodeResult {
        var feedback = ""
        for (attempt in 1..maxAttempts) {
            onProgress(TaskGraphProgress(
                ctx.index, ctx.node.task,
                if (attempt == 1) "执行中" else "重新指派",
                completed.get(), total, feedback, nodeId = ctx.node.id,
            ))
            val prompt = if (attempt == 1) ctx.node.task
                else "${ctx.node.task}\n\n上次失败或验收未通过：$feedback。请核查并补足证据。"
            val output = try {
                execute(ctx, prompt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: TaskGraphNodeExecutionException) {
                val detail = failed.message.orEmpty()
                onProgress(TaskGraphProgress(ctx.index, ctx.node.task, "受阻", completed.incrementAndGet(), total,
                    detail, nodeId = ctx.node.id))
                return TaskGraphNodeResult(ctx.node, ctx.index, TaskGraphOutcome.FAILED, error = detail, attempts = attempt)
            } catch (error: Exception) {
                if (!retrySafe || !shouldRetryError(error)) throw error
                feedback = (error.message ?: error::class.java.simpleName).take(500)
                continue
            }
            // Progress is written to Work's authoritative EventLog. A failed
            // durable append is a terminal persistence error, never a reason
            // to re-execute a tool that may have already succeeded.
            onProgress(TaskGraphProgress(ctx.index, ctx.node.task, "验收中", completed.get(), total,
                nodeId = ctx.node.id))
            val verdict = try {
                accept(ctx, output)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!retrySafe || !shouldRetryError(error)) throw error
                feedback = (error.message ?: error::class.java.simpleName).take(500)
                continue
            }
            if (verdict.accepted) {
                val result = TaskGraphNodeResult(ctx.node, ctx.index, TaskGraphOutcome.COMPLETED,
                    output = output, attempts = attempt)
                onResult(result, ctx.dependencyOutputs)
                val count = completed.incrementAndGet()
                onProgress(TaskGraphProgress(ctx.index, ctx.node.task, "已完成", count, total,
                    nodeId = ctx.node.id))
                return result
            }
            feedback = verdict.feedback.ifBlank { "验收未通过" }.take(500)
        }
        val count = completed.incrementAndGet()
        onProgress(TaskGraphProgress(ctx.index, ctx.node.task, "受阻", count, total, feedback,
            nodeId = ctx.node.id))
        return TaskGraphNodeResult(ctx.node, ctx.index, TaskGraphOutcome.FAILED,
            error = feedback, attempts = maxAttempts)
    }

    /** Reject invalid graphs before Work persists a start fact or invokes an Agent. */
    fun validateGraph(nodes: List<TaskGraphNode>): List<TaskGraphNode> {
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
        require(!TaskGraphTopology.containsCycle(unique.mapValues { (_, node) -> node.dependsOn })) {
            "Task graph contains a dependency cycle"
        }
        return nodes
    }
}
