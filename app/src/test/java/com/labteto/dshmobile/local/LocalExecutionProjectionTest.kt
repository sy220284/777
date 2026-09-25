package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalExecutionProjectionTest {
    @Test
    fun chatModeHidesWorkJobsWithoutStoppingThem() {
        val jobs = listOf(
            LocalJobInfo(
                id = "job-1",
                label = "子代理：后台检查",
                status = "running",
            ),
        )

        assertTrue(projectExecutionJobs(LocalUsageMode.CHAT, jobs).isEmpty())
        assertEquals(jobs, projectExecutionJobs(LocalUsageMode.WORK, jobs))
    }

    @Test
    fun chatModeHidesProcessWideWorkResourceCounters() {
        assertEquals(0, projectWorkResourceCount(LocalUsageMode.CHAT, 2))
        assertEquals(2, projectWorkResourceCount(LocalUsageMode.WORK, 2))
    }
}
