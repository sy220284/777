package com.labteto.dshmobile.ui.screens.local


import com.labteto.dshmobile.local.work.LocalWorkflowProgress
import com.labteto.dshmobile.ui.shenyan.SyProgressStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowProgressVisualTest {
    private fun state(stage: String, completed: Int, total: Int, reason: String? = null) =
        LocalWorkflowProgress(
            sessionId = "test",
            stage = stage,
            task = "Work",
            completed = completed,
            total = total,
            blockedReason = reason,
        )

    @Test fun completionRequiresBothCompletedStageAndCount() {
        assertEquals(SyProgressStatus.Running, workflowProgressVisualStatus(state("执行中", 3, 3)))
        assertEquals(SyProgressStatus.Running, workflowProgressVisualStatus(state("已完成", 2, 3)))
        assertEquals(SyProgressStatus.Done, workflowProgressVisualStatus(state("已完成", 3, 3)))
    }

    @Test fun failureAndUnknownTotalsNeverPretendSuccess() {
        assertEquals(SyProgressStatus.Failed, workflowProgressVisualStatus(state("受阻", 2, 3)))
        assertEquals(SyProgressStatus.Failed, workflowProgressVisualStatus(state("验收中", 3, 3, "needs repair")))
        assertEquals(SyProgressStatus.Idle, workflowProgressVisualStatus(state("执行中", 0, 0)))
    }
}
