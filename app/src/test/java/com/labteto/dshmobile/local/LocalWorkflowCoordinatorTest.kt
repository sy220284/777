package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.agent.LocalSubagentExecutionException
import com.labteto.dshmobile.local.work.LocalWorkflowCoordinator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkflowCoordinatorTest {
    @Test fun requiredEvidenceTriggersOneReassignmentAndReportsRealCheck() = runTest {
        var calls = 0
        val coordinator = LocalWorkflowCoordinator(
            execute = { calls++; if (calls == 1) "初稿" else "核对证据已补齐" },
            pruneOutput = { it },
            onProgress = {},
        )

        val result = coordinator.run(listOf("核对资料"), "pipeline", listOf("证据"))

        assertEquals(2, calls)
        assertTrue(result.contains("指定证据已命中"))
        assertTrue(result.contains("尝试 2 次"))
    }

    @Test
    fun nonRetryableSubagentFailureIsNotReassigned() = runTest {
        var calls = 0
        val coordinator = LocalWorkflowCoordinator(
            execute = {
                calls++
                throw LocalSubagentExecutionException(
                    errorCode = "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION",
                    retryable = false,
                    message = "模型服务已经受理，禁止整轮重放",
                )
            },
            pruneOutput = { it },
            onProgress = {},
        )

        val failure = runCatching {
            coordinator.run(listOf("只读核对"), "parallel", emptyList())
        }.exceptionOrNull()

        assertTrue(failure is LocalSubagentExecutionException)
        assertEquals(1, calls)
    }


    @Test
    fun graphExecutesDiamondAfterBothDependenciesAreVerified() = runTest {
        val observed = mutableListOf<String>()
        val coordinator = LocalWorkflowCoordinator(
            execute = { prompt ->
                observed += prompt
                when {
                    "总结" in prompt -> {
                        assertTrue(prompt.contains("task-2 的已验收结果"))
                        assertTrue(prompt.contains("task-3 的已验收结果"))
                        "最终总结"
                    }
                    "资料甲" in prompt -> "资料甲成果"
                    "资料乙" in prompt -> "资料乙成果"
                    else -> "原始数据"
                }
            }, pruneOutput = { it }, onProgress = {},
        )
        val result = coordinator.run(
            tasks = listOf("采集", "资料甲", "资料乙", "总结"),
            mode = "graph", requiredEvidence = emptyList(),
            dependencies = listOf(emptyList(), listOf(0), listOf(0), listOf(1, 2)),
        )
        assertTrue(result.contains("最终总结"))
        assertEquals(4, observed.size)
    }

    @Test
    fun graphPartialResultIsStructuredAndCannotMasqueradeAsFullyDelivered() = runTest {
        val workflow = LocalWorkflowCoordinator(
            execute = { prompt ->
                // Retries add feedback to the original task; A must continue failing.
                // Only the independent C is allowed to complete.
                when (prompt.lineSequence().first()) {
                    "A" -> ""
                    "B" -> error("Dependent node B must stay blocked")
                    else -> "完成"
                }
            },
            pruneOutput = { it }, onProgress = {},
        )
        val prepared = workflow.prepare(
            tasks = listOf("A", "B", "C"), mode = "graph",
            requiredEvidence = emptyList(),
            dependencies = listOf(emptyList(), listOf(0), emptyList()),
        )
        val result = workflow.runDetailed(prepared)
        assertEquals(3, result.total)
        assertEquals(1, result.completed)
        assertEquals(1, result.failed)
        assertEquals(1, result.blocked)
        assertEquals("partial", result.executionOutcome)
        assertTrue(result.summary.contains("工作流仍有未完成节点"))
        assertTrue("Blocked node identity must survive user-facing delivery", result.summary.contains("子任务 2：B"))
        assertTrue("Failed node identity must survive user-facing delivery", result.summary.contains("子任务 1：A"))
        assertTrue(result.summary.contains("依赖未完成：task-1"))
    }

    @Test
    fun goalRevisionFenceRejectsChangedAcceptanceWithoutReplayingTheTask() {
        val captured = com.labteto.dshmobile.local.work.LocalGoal(
            description = "构建并验证", id = "goal-1",
            artifactPaths = listOf("output.md"), verificationCommands = listOf("verify"),
        )
        com.labteto.dshmobile.local.work.requireCurrentWorkflowGoal(captured, captured)
        val stale = runCatching {
            com.labteto.dshmobile.local.work.requireCurrentWorkflowGoal(
                captured, captured.copy(artifactPaths = listOf("new-output.md")))
        }.exceptionOrNull()
        assertTrue(stale is com.labteto.dshmobile.local.work.LocalWorkflowGoalSuperseded)
        assertTrue(runCatching {
            com.labteto.dshmobile.local.work.requireCurrentWorkflowGoal(
                captured, captured.copy(status = "paused"))
        }.exceptionOrNull() is com.labteto.dshmobile.local.work.LocalWorkflowGoalSuperseded)
    }

    @Test
    fun graphWithNoGoalCannotSilentlyAttachToNewUserGoal() {
        com.labteto.dshmobile.local.work.requireCurrentWorkflowGoal(null, null)
        assertTrue(runCatching {
            com.labteto.dshmobile.local.work.requireCurrentWorkflowGoal(
                null, com.labteto.dshmobile.local.work.LocalGoal("后来建立的目标", id = "new"))
        }.exceptionOrNull() is com.labteto.dshmobile.local.work.LocalWorkflowGoalSuperseded)
    }

    @Test
    fun allVerifiedGraphNodesCompleteTheirGraphButDoNotSetTheParentGoal() = runTest {
        val workflow = LocalWorkflowCoordinator(
            execute = { "实际核验记录" }, pruneOutput = { it }, onProgress = {},
        )
        val result = workflow.runDetailed(
            workflow.prepare(listOf("检查一", "检查二"), "parallel",
                listOf("核验", "核验")),
        )
        assertEquals("nodes_completed", result.executionOutcome)
        assertEquals(2, result.completed)
        assertEquals(0, result.failed)
        assertEquals(0, result.blocked)
        assertTrue(result.allNodesCompleted)
    }

    @Test
    fun graphPreflightRejectsInvalidDependencyBeforeAnyStartIsPersisted() {
        var executions = 0
        val coordinator = LocalWorkflowCoordinator(
            execute = { executions++; "should not execute" },
            pruneOutput = { it }, onProgress = {},
        )
        val invalid = runCatching {
            coordinator.prepare(listOf("A", "B"), "graph", emptyList(),
                dependencies = listOf(listOf(1), listOf(0)))
        }.exceptionOrNull()
        assertTrue(invalid is IllegalArgumentException)
        assertEquals(0, executions)
        val accepted = coordinator.prepare(listOf("A", "B"), "graph", emptyList(),
            dependencies = listOf(emptyList(), listOf(0)))
        assertEquals(listOf("task-1"), accepted.nodes[1].dependsOn)
    }

    @Test
    fun graphRejectsDependencyCycleBeforeLaunchingAnyAgent() = runTest {
        var launched = 0
        val coordinator = LocalWorkflowCoordinator(
            execute = { launched++; "执行" },
            pruneOutput = { it }, onProgress = {},
        )
        val failure = runCatching {
            coordinator.run(listOf("A", "B"), "graph", emptyList(),
                listOf(listOf(1), listOf(0)))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, launched)
    }

    @Test
    fun graphRejectsIndexOutsideTaskListBeforeAnyAgentStarts() = runTest {
        var launched = 0
        val coordinator = LocalWorkflowCoordinator(
            execute = { launched++; "执行" },
            pruneOutput = { it }, onProgress = {},
        )
        val failure = runCatching {
            coordinator.run(listOf("A", "B"), "graph", emptyList(),
                listOf(emptyList(), listOf(2)))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, launched)
    }

    @Test
    fun workflowSupports128NodesButRejects129BeforeScheduling() {
        val coordinator = LocalWorkflowCoordinator(
            execute = { "完成" }, pruneOutput = { it }, onProgress = {},
            maxParallelism = 8,
        )
        val accepted = coordinator.prepare(List(128) { "检查第${it + 1}项" }, "parallel", emptyList())
        assertEquals(128, accepted.nodes.size)
        assertTrue(runCatching {
            coordinator.prepare(List(129) { "检查第${it + 1}项" }, "parallel", emptyList())
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedEvidenceCannotSilentlyPass() = runTest {
        LocalWorkflowCoordinator(execute = { "完成" }, pruneOutput = { it }, onProgress = {})
            .run(listOf("一", "二"), "parallel", listOf("一项证据"))
    }
}
