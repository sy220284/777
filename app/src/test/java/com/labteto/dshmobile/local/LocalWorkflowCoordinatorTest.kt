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

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedEvidenceCannotSilentlyPass() = runTest {
        LocalWorkflowCoordinator(execute = { "完成" }, pruneOutput = { it }, onProgress = {})
            .run(listOf("一", "二"), "parallel", listOf("一项证据"))
    }
}
