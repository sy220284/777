package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.runtime.projectExecutionJobs
import com.labteto.dshmobile.local.runtime.projectWorkResourceCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalExecutionProjectionTest {
    @Test
    fun chatModeHidesWorkJobsAndWorkModeOnlyShowsCurrentSessionJobs() {
        val jobs = listOf(
            LocalJobInfo(
                id = "job-1",
                label = "子代理：后台检查",
                status = "running",
                ownerSessionId = "session-a",
            ),
            LocalJobInfo(
                id = "job-2",
                label = "后台命令",
                status = "running",
                ownerSessionId = "session-b",
            ),
        )

        assertTrue(projectExecutionJobs(LocalUsageMode.CHAT, "session-a", jobs).isEmpty())
        assertEquals(listOf(jobs.first()), projectExecutionJobs(LocalUsageMode.WORK, "session-a", jobs))
        assertEquals(listOf(jobs.last()), projectExecutionJobs(LocalUsageMode.WORK, "session-b", jobs))
    }

    @Test
    fun chatModeHidesProcessWideWorkResourceCounters() {
        assertEquals(0, projectWorkResourceCount(LocalUsageMode.CHAT, 2))
        assertEquals(2, projectWorkResourceCount(LocalUsageMode.WORK, 2))
    }
}
