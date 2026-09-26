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
    fun parallelResumeSkipsCompletedBranches() = runTest {
        val runner = HarnessWorkflowRunner(maxTasks = 4, maxParallelism = 2)
        val executed = mutableListOf<Int>()
        val resume = HarnessWorkflowCheckpoint(
            mode = HarnessWorkflowMode.PARALLEL,
            tasks = listOf("a", "b", "c"),
            results = listOf(HarnessWorkflowTaskResult(0, "a", output = "done-a")),
        )
        val checkpoints = mutableListOf<HarnessWorkflowCheckpoint>()

        val results = runner.run(
            tasks = resume.tasks,
            mode = HarnessWorkflowMode.PARALLEL,
            resume = resume,
            onCheckpoint = { checkpoints += it },
        ) { index, task, _ ->
            executed += index
            "done-" + task
        }

        assertEquals(listOf(1, 2), executed.sorted())
        assertEquals(listOf("done-a", "done-b", "done-c"), results.map { it.output })
        assertTrue(checkpoints.isNotEmpty())
    }

    @Test
    fun pipelineResumeContinuesFromLastCompletedStage() = runTest {
        val runner = HarnessWorkflowRunner()
        val executed = mutableListOf<Int>()
        val resume = HarnessWorkflowCheckpoint(
            mode = HarnessWorkflowMode.PIPELINE,
            tasks = listOf("a", "b", "c"),
            results = listOf(
                HarnessWorkflowTaskResult(0, "a", output = "A"),
                HarnessWorkflowTaskResult(1, "b", output = "B"),
            ),
        )

        val results = runner.run(
            tasks = resume.tasks,
            mode = HarnessWorkflowMode.PIPELINE,
            resume = resume,
        ) { index, _, previous ->
            executed += index
            previous.orEmpty() + "C"
        }

        assertEquals(listOf(2), executed)
        assertEquals("BC", results.last().output)
    }

}
