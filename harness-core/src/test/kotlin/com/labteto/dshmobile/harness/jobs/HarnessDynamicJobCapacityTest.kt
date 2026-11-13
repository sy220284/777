package com.labteto.dshmobile.harness.jobs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessDynamicJobCapacityTest {
    @Test
    fun runningJobsSurviveCapacityReductionButNewStartsObeyLiveLimit() = runTest {
        val release = CompletableDeferred<Unit>()
        val jobs = HarnessJobManager(
            scope = this,
            onChanged = {},
            maxConcurrentJobs = 3,
        )
        repeat(2) { index ->
            jobs.start("正在执行的任务-$index") { _, _ ->
                release.await()
                "完成"
            }
        }
        runCurrent()
        assertEquals(1, jobs.availableSlots())

        jobs.updateConcurrentLimit(1)
        assertEquals(0, jobs.availableSlots())
        assertTrue(jobs.start("第三个任务") { _, _ -> "错误启动" }.contains("并发已满"))

        release.complete(Unit)
        runCurrent()
        assertEquals(1, jobs.availableSlots())
        jobs.updateConcurrentLimit(3)
        assertEquals(3, jobs.availableSlots())
    }
}
