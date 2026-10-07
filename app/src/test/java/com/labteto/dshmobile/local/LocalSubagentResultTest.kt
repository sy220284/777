package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.agent.LocalSubagentResult
import com.labteto.dshmobile.local.agent.LocalSubagentStatus
import com.labteto.dshmobile.local.agent.requireCompletedOutput
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    fun completedStructuredOutcomeKeepsTypedValue() {
        val structured = buildJsonObject {
            put("verified", true)
            put("conclusion", "完成")
        }
        val result = LocalSubagentResult(
            status = LocalSubagentStatus.COMPLETED,
            output = structured.toString(),
            structuredResult = structured,
        )

        assertEquals(structured.toString(), result.requireCompletedOutput())
        assertEquals(structured, result.structuredResult)
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
