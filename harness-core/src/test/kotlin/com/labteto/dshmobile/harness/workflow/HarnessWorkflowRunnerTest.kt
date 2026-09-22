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
}
