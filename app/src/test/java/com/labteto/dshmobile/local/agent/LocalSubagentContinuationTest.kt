package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.model.LocalModelAdmissionState
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSubagentContinuationTest {
    @Test fun malformedToolArgumentsReturnTargetedRecoveryAndStopAtBound() {
        val problem = LocalModelException(
            code = "ANTHROPIC_TOOL_ARGUMENTS_INVALID",
            message = "工具参数不完整",
            retryable = false,
            continuationEligible = true,
            admissionState = LocalModelAdmissionState.ADMITTED,
            toolInputIssue = "missing_arguments_value",
        )
        val next = subagentContinuationMessage(problem, 0)
        assertEquals("user", next?.getValue("role")?.jsonPrimitive?.content)
        val prompt = next?.getValue("content")?.jsonPrimitive?.content.orEmpty()
        assertTrue(prompt.contains("missing_arguments_value"))
        assertTrue(prompt.contains("tool_search"))
        assertTrue(prompt.contains("已有") || prompt.contains("已经成功"))
        assertNull(subagentContinuationMessage(problem, 2))
    }
}
