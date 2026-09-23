package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LocalSubagentResultTest {
    @Test
    fun completedOutcomeCrossesBoundaryWithOutput() {
        val result = LocalSubagentResult(
            status = LocalSubagentStatus.COMPLETED,
            output = "完成",
        )

        assertEquals("完成", result.requireCompletedOutput())
    }

    @Test
    fun failedOutcomeCannotCrossWorkflowBoundaryAsSuccess() {
        val result = LocalSubagentResult(
            status = LocalSubagentStatus.STEP_LIMIT,
            output = "[subagent][sa-test][STEP_LIMIT] 任务未完整结束",
            errorCode = "STEP_LIMIT",
        )

        try {
            result.requireCompletedOutput()
            fail("失败的子代理结果不应被当成成功输出")
        } catch (expected: IllegalStateException) {
            assertEquals(result.output, expected.message)
        }
    }
}
