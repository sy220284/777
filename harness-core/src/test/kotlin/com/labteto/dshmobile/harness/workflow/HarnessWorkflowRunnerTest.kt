package com.labteto.dshmobile.harness.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HarnessWorkflowRunnerTest {
    @Test
    fun parallelBranchesAreFailureIsolatedAndKeepInputOrder() = runTest {
        val runner = HarnessWorkflowRunner(maxTasks = 4, maxParallelism = 2)

        val result = runner.run(
            tasks = listOf("甲", "乙", "丙"),
            mode = HarnessWorkflowMode.PARALLEL,
        ) { index, task, _ ->
            if (index == 1) error("乙失败")
            "完成-$task"
        }

        assertEquals(listOf("甲", "乙", "丙"), result.map { it.task })
        assertEquals("完成-甲", result[0].output)
        assertEquals("乙失败", result[1].error)
        assertEquals("完成-丙", result[2].output)
        assertTrue(result[0].succeeded)
        assertFalse(result[1].succeeded)
    }

    @Test
    fun pipelineFeedsPreviousOutputIntoNextStage() = runTest {
        val runner = HarnessWorkflowRunner()

        val result = runner.run(
            tasks = listOf("一", "二", "三"),
            mode = HarnessWorkflowMode.PIPELINE,
        ) { _, task, previous ->
            if (previous == null) task else "$previous>$task"
        }

        assertEquals(listOf("一", "一>二", "一>二>三"), result.map { it.output })
    }

    @Test
    fun pipelineStopsAfterThrownStageFailure() = runTest {
        val runner = HarnessWorkflowRunner()

        val result = runner.run(
            tasks = listOf("一", "二", "三"),
            mode = HarnessWorkflowMode.PIPELINE,
        ) { index, task, _ ->
            if (index == 1) error("阶段失败")
            "完成-$task"
        }

        assertEquals(2, result.size)
        assertEquals("阶段失败", result.last().error)
    }

    @Test(expected = CancellationException::class)
    fun externalCancellationPropagates() = runTest {
        HarnessWorkflowRunner().run(
            tasks = listOf("一"),
            mode = HarnessWorkflowMode.PARALLEL,
        ) { _, _, _ ->
            throw CancellationException("stop")
        }
    }

    @Test
    fun failedAcceptanceReassignsOnceAndPassesFeedbackToReadOnlyExecutor() = runTest {
        val prompts = mutableListOf<String>()
        val stages = mutableListOf<String>()
        val result = HarnessWorkflowRunner().run(
            tasks = listOf("核对产出"),
            mode = HarnessWorkflowMode.PIPELINE,
            maxAttempts = 2,
            accept = { _, _, output -> HarnessWorkflowAcceptance(output.contains("证据"), "缺少证据") },
            onProgress = { stages += it.stage },
        ) { _, task, _ ->
            prompts += task
            if (prompts.size == 1) "初稿" else "补齐证据"
        }

        assertEquals(2, result.single().attempts)
        assertTrue(result.single().succeeded)
        assertTrue(prompts.last().contains("缺少证据"))
        assertEquals(listOf("执行中", "验收中", "重新指派", "验收中", "已完成"), stages)
    }

    @Test
    fun pipelineStopsWhenReassignedStageStillFailsAcceptance() = runTest {
        val executed = mutableListOf<String>()
        val results = HarnessWorkflowRunner().run(
            tasks = listOf("一", "二"),
            mode = HarnessWorkflowMode.PIPELINE,
            maxAttempts = 2,
            accept = { _, _, _ -> HarnessWorkflowAcceptance(false, "验收失败") },
        ) { _, task, _ ->
            executed += task
            "有内容"
        }

        assertEquals(1, results.size)
        assertEquals(2, executed.size)
        assertEquals("验收失败", results.single().error)
    }

    @Test
    fun emptyOutputDoesNotCountAsSuccessfulCompletion() = runTest {
        val result = HarnessWorkflowRunner().run(listOf("任务"), HarnessWorkflowMode.PARALLEL) { _, _, _ -> "" }
        assertFalse(result.single().succeeded)
    }
}
