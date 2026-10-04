package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalGoal
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalTodoItem
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.quality.LocalOutputQualityContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkCompletionClaimGuardTest {
    @Test
    fun completionClaimIsDiagnosedWhenRuntimeStateStillHasOpenWork() {
        val state = LocalHarnessState(
            usageMode = LocalUsageMode.WORK,
            goal = LocalGoal("完成重构", status = "blocked"),
            todos = listOf(
                LocalTodoItem("补测试", "pending"),
                LocalTodoItem("已处理", "completed"),
            ),
        )
        val result = LocalWorkCompletionClaimGuard.inspect(
            "全部完成，可以交付。",
            LocalOutputQualityContext(usageMode = LocalUsageMode.WORK, state = state),
        )
        assertEquals("全部完成，可以交付。", result.text)
        assertTrue(result.findings.contains("完成声明与未完成任务清单冲突"))
        assertTrue(result.findings.contains("完成声明与阻塞目标状态冲突"))
    }

    @Test
    fun negativeCompletionStatementDoesNotProduceFalseFinding() {
        val state = LocalHarnessState(
            usageMode = LocalUsageMode.WORK,
            todos = listOf(LocalTodoItem("继续处理", "pending")),
        )
        val result = LocalWorkCompletionClaimGuard.inspect(
            "尚未完成，还要继续处理。",
            LocalOutputQualityContext(usageMode = LocalUsageMode.WORK, state = state),
        )
        assertTrue(result.findings.isEmpty())
    }
}
