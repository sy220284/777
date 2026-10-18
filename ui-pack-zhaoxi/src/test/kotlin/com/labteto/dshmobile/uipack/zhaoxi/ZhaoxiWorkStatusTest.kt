package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBadgeTone
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxWorkTodoProgress
import com.labteto.dshmobile.uicontract.UiWorkPlanSnapshot
import com.labteto.dshmobile.uicontract.UiWorkPlanStep
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxWorkStepIsDone
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxWorkStepLabel
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxWorkStepTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhaoxiWorkStatusTest {
    @Test fun onlyOwnerConfirmedSuccessCountsAsFinished() {
        for (status in listOf("completed", "DONE", "success", "finished", "已完成"))
            assertTrue(status, zxWorkStepIsDone(status))
        for (status in listOf("", "running", "pending", "failed", "blocked", "unknown"))
            assertFalse(status, zxWorkStepIsDone(status))
    }

    @Test fun independentPlanAndTodosNeverBorrowStatusesByIndex() {
        val snapshot = UiWorkPlanSnapshot(
            sessionId = "work",
            plan = listOf("准备材料", "编写方案", "确认结果"),
            todos = listOf(
                UiWorkPlanStep("验证网络", "completed"),
                UiWorkPlanStep("同步文件", "running"),
            ),
            planMode = true,
        )
        assertEquals(3, snapshot.plan.size)
        assertEquals(2, snapshot.todos.size)
        assertEquals(1, zxWorkTodoProgress(snapshot).done)
        assertEquals(2, zxWorkTodoProgress(snapshot).reported)
        assertEquals(2, zxWorkTodoProgress(snapshot).total)
        val noTodos = snapshot.copy(todos = emptyList())
        assertEquals(0, zxWorkTodoProgress(noTodos).total)
    }

    @Test fun stepsExposeReadableStatusWithoutInventingCompletion() {
        assertEquals("待同步", zxWorkStepLabel(""))
        assertEquals("进行中", zxWorkStepLabel("in_progress"))
        assertEquals("已完成", zxWorkStepLabel("completed"))
        assertEquals("受阻", zxWorkStepLabel("blocked"))
        assertEquals(ZxBadgeTone.Err, zxWorkStepTone("FAILED"))
        assertEquals(ZxBadgeTone.Neutral, zxWorkStepTone("pending"))
        assertEquals("custom-stage", zxWorkStepLabel("custom-stage"))
    }
}
