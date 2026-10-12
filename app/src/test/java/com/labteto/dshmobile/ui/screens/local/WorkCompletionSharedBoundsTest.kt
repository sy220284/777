package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.presentation.WorkflowStage
import com.labteto.dshmobile.local.work.LocalWorkflowProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkCompletionSharedBoundsTest {
    private fun done(session: String = "work-1") = LocalWorkflowProgress(
        sessionId = session,
        stage = WorkflowStage.COMPLETED,
        task = "完成验证并交付文件",
        completed = 3,
        total = 3,
    )

    @Test fun completedWorkMorphsOnlyForItsOwnSession() {
        assertEquals("work-completion:work-1", workCompletionCardKey("work-1", done()))
        assertNotEquals(workCompletionCardKey("work-1", done()), workCompletionCardKey("work-2", done("work-2")))
        assertNull(workCompletionCardKey("work-2", done()))
        assertNull(workCompletionCardKey("", done()))
        assertNull(workCompletionCardKey("work-1", null))
    }

    @Test fun pendingBlockedAndEmptyCountsMustNotMorphAsFinishedWork() {
        assertNull(workCompletionCardKey("work-1", done().copy(stage = WorkflowStage.EXECUTING)))
        assertNull(workCompletionCardKey("work-1", done().copy(completed = 2)))
        assertNull(workCompletionCardKey("work-1", done().copy(total = 0, completed = 0)))
        assertNull(workCompletionCardKey("work-1", done().copy(blockedReason = "审批待处理")))
        assertNull(workCompletionCardKey("work-1", done().copy(stage = WorkflowStage.STALLED)))
    }
}
