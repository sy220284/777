package com.labteto.dshmobile.local.work

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class LocalWorkPlanApprovalCommitTest {
    @Test
    fun failedAuthorityWritePreservesPlanAndPlanningMode() {
        val flow = MutableStateFlow(LocalWorkRunState(sessionId = "plan", work = LocalWorkState(planMode = true, plan = listOf("旧计划"))))
        val failure = IllegalStateException("disk failure")
        val result = runCatching {
            commitApprovedWorkPlan(localWorkRunStatePort(flow), listOf("新计划")) { throw failure }
        }
        assertSame(failure, result.exceptionOrNull())
        assertTrue(flow.value.work.planMode)
        assertEquals(listOf("旧计划"), flow.value.work.plan)
    }

    @Test
    fun publishesBothFactsOnlyAfterCommit() {
        val flow = MutableStateFlow(LocalWorkRunState(sessionId = "plan", work = LocalWorkState(planMode = true, plan = listOf("旧计划"))))
        commitApprovedWorkPlan(localWorkRunStatePort(flow), listOf("新计划")) { items ->
            assertEquals(listOf("新计划"), items)
            assertTrue(flow.value.work.planMode)
            assertEquals(listOf("旧计划"), flow.value.work.plan)
        }
        assertFalse(flow.value.work.planMode)
        assertEquals(listOf("新计划"), flow.value.work.plan)
    }
}
