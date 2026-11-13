package com.labteto.dshmobile.harness.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskGraphExecutorTest {
    private val graph = TaskGraphExecutor(maxNodes = 10, maxParallelism = 2)

    @Test
    fun sharedTopologyRejectsTheSameCyclesAsTheWorkGraph() {
        val cycle = mapOf("a" to listOf("b"), "b" to listOf("c"),
            "c" to listOf("a"), "independent" to emptyList())
        assertTrue(TaskGraphTopology.containsCycle(cycle))
        assertFalse(TaskGraphTopology.containsCycle(mapOf(
            "a" to emptyList(), "b" to listOf("a"),
            "c" to listOf("a", "b"),
        )))
        // Team reducers filter deleted tasks before passing the active topology.
        assertFalse(TaskGraphTopology.containsCycle(mapOf("a" to listOf("deleted"))))
    }

    @Test
    fun dependenciesReadPreviousResultsAndReturnOriginalOrder() = runTest {
        val nodes = listOf(
            TaskGraphNode("review", "审查", listOf("write", "test")),
            TaskGraphNode("test", "测试", listOf("write")),
            TaskGraphNode("write", "写入"),
        )
        val executed = mutableListOf<String>()
        val results = graph.run(nodes) { ctx, _ ->
            executed += ctx.node.id
            when (ctx.node.id) {
                "write" -> "代码"
                "test" -> { assertEquals("代码", ctx.dependencyOutputs["write"]); "测试通过" }
                else -> { assertEquals("测试通过", ctx.dependencyOutputs["test"]); "审查通过" }
            }
        }
        assertEquals(listOf("review", "test", "write"), results.map { it.node.id })
        assertEquals(listOf("write", "test", "review"), executed)
        assertTrue(results.all { it.succeeded })
    }

    @Test
    fun rejectedCycleOrMissingDependencyCannotStartAnyTool() = runTest {
        var calls = 0
        val cyclic = listOf(TaskGraphNode("a", "A", listOf("b")),
            TaskGraphNode("b", "B", listOf("a")))
        assertTrue(runCatching { graph.run(cyclic) { _, _ -> calls++; "bad" } }.isFailure)
        assertTrue(runCatching { graph.run(listOf(
            TaskGraphNode("a", "A", listOf("unknown")))) { _, _ -> calls++; "bad" } }.isFailure)
        assertEquals(0, calls)
    }

    @Test
    fun failureBlocksDependentsButAllowsIndependentBranch() = runTest {
        val calls = mutableListOf<String>()
        val nodes = listOf(
            TaskGraphNode("a", "A"),
            TaskGraphNode("b", "B", listOf("a")),
            TaskGraphNode("c", "C"),
            TaskGraphNode("d", "D", listOf("b")),
        )
        val result = graph.run(nodes) { ctx, _ ->
            calls += ctx.node.id
            if (ctx.node.id == "a") "" else "ok"
        }
        assertEquals(listOf("a", "c"), calls.sorted())
        assertEquals(
            listOf(TaskGraphOutcome.FAILED, TaskGraphOutcome.BLOCKED,
                TaskGraphOutcome.COMPLETED, TaskGraphOutcome.BLOCKED),
            result.map { it.outcome },
        )
        assertEquals(4, result.size)
        assertEquals(0, result[1].attempts)
    }

    @Test
    fun readOnlyAcceptanceRetryPassesFeedback() = runTest {
        var calls = 0
        val p = mutableListOf<String>()
        val r = graph.run(
            listOf(TaskGraphNode("task", "核对")),
            maxAttempts = 2, retrySafe = true,
            accept = { _, v -> TaskGraphAcceptance(v.contains("证据"), "缺少证据") },
        ) { _, prompt ->
            calls++
            p += prompt
            if (calls == 1) "初稿" else "证据"
        }
        assertEquals(2, calls)
        assertTrue(p.last().contains("缺少证据"))
        assertEquals(TaskGraphOutcome.COMPLETED, r.single().outcome)
        assertEquals(2, r.single().attempts)
    }

    @Test
    fun mutatingExecutorCannotBeRetriedByConfiguration() = runTest {
        var count = 0
        val failure = runCatching {
            graph.run(listOf(TaskGraphNode("task", "写入")), maxAttempts = 2) { _, _ ->
                count++
                "done"
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, count)
    }

    @Test
    fun nonRetryableExecutorFailurePropagatesWithoutSyntheticSuccess() = runTest {
        var count = 0
        val failure = runCatching {
            graph.run(listOf(TaskGraphNode("task", "工具")), maxAttempts = 2,
                retrySafe = true, shouldRetryError = { false }) { _, _ ->
                count++
                error("terminal")
            }
        }.exceptionOrNull()
        assertEquals("terminal", failure?.message)
        assertEquals(1, count)
    }

    @Test(expected = CancellationException::class)
    fun cancellationPropagates() = runTest {
        graph.run(listOf(TaskGraphNode("a", "A"))) { _, _ ->
            throw CancellationException("stopped")
        }
    }

    @Test
    fun allNodesAreAdmittedOrEntireGraphRejected() = runTest {
        var count = 0
        assertTrue(runCatching {
            graph.run((1..11).map { TaskGraphNode("task-$it", "任务-$it") }) { _, _ ->
                count++; "ok"
            }
        }.isFailure)
        assertEquals(0, count)
    }

    @Test
    fun failedProgressPersistenceAfterSuccessfulExecutionNeverRetriesTheTool() = runTest {
        var invocations = 0
        val failure = runCatching {
            graph.run(
                listOf(TaskGraphNode("a", "只读子任务")),
                maxAttempts = 3,
                retrySafe = true,
                onProgress = { event ->
                    if (event.stage == "验收中") error("durable event append failed")
                },
            ) { _, _ ->
                invocations++
                "真实执行已成功"
            }
        }.exceptionOrNull()
        assertEquals("durable event append failed", failure?.message)
        assertEquals(1, invocations)
    }

    @Test
    fun failedCompletedEventPersistenceDoesNotReplaySettledNode() = runTest {
        var invocations = 0
        val failure = runCatching {
            graph.run(
                listOf(TaskGraphNode("a", "已结算工作")),
                maxAttempts = 3,
                retrySafe = true,
                onProgress = { event ->
                    if (event.stage == "已完成") error("completion event append failed")
                },
            ) { _, _ ->
                invocations++
                "结果"
            }
        }.exceptionOrNull()
        assertEquals("completion event append failed", failure?.message)
        assertEquals(1, invocations)
    }

    @Test
    fun everyGraphProgressFactUsesStableNodeIdentity() = runTest {
        val progress = mutableListOf<TaskGraphProgress>()
        val r = graph.run(listOf(
            TaskGraphNode("stable-a", "任务一"),
            TaskGraphNode("stable-b", "任务二", dependsOn = listOf("stable-a")),
        ), onProgress = { synchronized(progress) { progress += it } }) { _, _ -> "已核实" }
        assertTrue(r.all { it.succeeded })
        assertEquals(setOf("stable-a", "stable-b"), progress.map { it.nodeId }.toSet())
        assertTrue(progress.all { it.nodeId.isNotBlank() })
    }

    @Test
    fun dependentRunsImmediatelyWhenItsOwnPrerequisiteFinishes() = runTest {
        val slowRelease = CompletableDeferred<Unit>()
        val slowStarted = CompletableDeferred<Unit>()
        val dependentStarted = CompletableDeferred<Unit>()
        val graphRun = async {
            graph.run(listOf(
                TaskGraphNode("fast", "已就绪的前置任务"),
                TaskGraphNode("slow", "无关慢任务"),
                TaskGraphNode("dependent", "立即跟进", listOf("fast")),
            )) { context, _ ->
                when (context.node.id) {
                    "fast" -> "已完成"
                    "slow" -> {
                        slowStarted.complete(Unit)
                        slowRelease.await()
                        "无关任务完成"
                    }
                    else -> {
                        assertEquals("已完成", context.dependencyOutputs["fast"])
                        dependentStarted.complete(Unit)
                        "下一阶段完成"
                    }
                }
            }
        }
        runCurrent()
        assertTrue(slowStarted.isCompleted)
        assertTrue(dependentStarted.isCompleted)
        assertFalse(graphRun.isCompleted)
        slowRelease.complete(Unit)
        val result = graphRun.await()
        assertEquals(listOf("fast", "slow", "dependent"), result.map { it.node.id })
        assertTrue(result.all { it.succeeded })
    }

    @Test
    fun dependentBlocksWithoutExecutionWhileUnrelatedBranchStillSucceeds() = runTest {
        val launched = mutableListOf<String>()
        val result = graph.run(listOf(
            TaskGraphNode("failed", "前置失败"),
            TaskGraphNode("dependent", "必须阻塞", listOf("failed")),
            TaskGraphNode("independent", "无关分支"),
        )) { context, _ ->
            launched += context.node.id
            if (context.node.id == "failed") "" else "已完成"
        }
        assertEquals(listOf("failed", "independent"), launched.sorted())
        assertEquals(listOf(
            TaskGraphOutcome.FAILED, TaskGraphOutcome.BLOCKED,
            TaskGraphOutcome.COMPLETED,
        ), result.map { it.outcome })
        assertEquals(0, result[1].attempts)
    }

    @Test
    fun parallelBranchesStayIndependentAndProgressTracksAllSettlements() = runTest {
        val steps = mutableListOf<TaskGraphProgress>()
        val r = graph.run(listOf(TaskGraphNode("one", "一"),
            TaskGraphNode("two", "二"), TaskGraphNode("three", "三")),
            onProgress = { synchronized(steps) { steps += it } }) { ctx, _ ->
            if (ctx.node.id == "two") "ok" else "valid"
        }
        assertTrue(r.all { it.succeeded })
        assertEquals(3, steps.count { it.stage == "已完成" })
        assertEquals(3, steps.count { it.stage == "验收中" })
        assertEquals(3, steps.maxOf { it.completed })
    }
    @Test fun recoveredNodesAreNotExecutedAndTheirFullOutputFeedsDependents() = runTest {
        val nodes = listOf(TaskGraphNode("a", "research"), TaskGraphNode("b", "summarize", listOf("a")))
        val saved = TaskGraphNodeResult(nodes[0], 0, TaskGraphOutcome.COMPLETED, "durable output")
        val called = mutableListOf<String>()
        val results = graph.run(nodes, initialResults = mapOf("a" to saved)) { context, _ ->
            called += context.node.id
            assertEquals("durable output", context.dependencyOutputs["a"])
            "summary"
        }
        assertEquals(listOf("b"), called)
        assertEquals(saved, results[0])
    }

    @Test fun recoveredDescendantWithoutItsAcceptedDependencyIsRejectedBeforeExecution() = runTest {
        val nodes = listOf(TaskGraphNode("a", "research"), TaskGraphNode("b", "summarize", listOf("a")))
        var calls = 0
        val error = runCatching {
            graph.run(nodes, initialResults = mapOf("b" to TaskGraphNodeResult(nodes[1], 1,
                TaskGraphOutcome.COMPLETED, "summary"))) { _, _ -> calls++; "output" }
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, calls)
    }

    @Test fun persistentNodeFailureBlocksDependentButIndependentBranchCompletes() = runTest {
        val nodes = listOf(TaskGraphNode("a", "failed job"), TaskGraphNode("b", "dependent", listOf("a")),
            TaskGraphNode("c", "independent"))
        val results = graph.run(nodes) { context, _ ->
            if (context.node.id == "a") throw TaskGraphNodeExecutionException("durable job failed")
            check(context.node.id != "b")
            "done"
        }
        assertEquals(listOf(TaskGraphOutcome.FAILED, TaskGraphOutcome.BLOCKED, TaskGraphOutcome.COMPLETED), results.map { it.outcome })
    }

    @Test fun cancellationNeverCommitsAcceptanceEvidence() = runTest {
        var accepted = false
        val failure = runCatching {
            graph.run(listOf(TaskGraphNode("a", "cancel")), onResult = { _, _ -> accepted = true }) { _, _ ->
                throw CancellationException("cancelled")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(accepted)
    }

}
