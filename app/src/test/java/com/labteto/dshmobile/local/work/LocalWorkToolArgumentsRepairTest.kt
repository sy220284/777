package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.model.LocalModelAdmissionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkToolArgumentsRepairTest {
    private val original: List<JsonObject> = listOf(buildJsonObject {
        put("role", "user")
        put("content", "检查并修复工作目录")
    })

    private fun invalidArguments() = LocalModelException(
        code = "ANTHROPIC_TOOL_ARGUMENTS_INVALID",
        message = "tool_use 参数不是合法 JSON 对象",
        retryable = false,
        admissionState = LocalModelAdmissionState.ADMITTED,
        continuationEligible = true,
    )

    @Test fun correctsWithinSameModelStepWithoutChangingOriginalHistory() = runTest {
        val revisions = MutableStateFlow(1L)
        val seen = mutableListOf<List<JsonObject>>()
        val attempts = mutableListOf<Int>()
        val result = sampleWithToolArgumentsRepair(
            revisions = revisions,
            observedRevision = 1L,
            originalMessages = original,
            canRepair = { true },
            onRepair = attempts::add,
        ) { messages ->
            seen += messages
            if (seen.size == 1) throw invalidArguments()
            "已执行成功"
        }
        assertEquals(LocalWorkSamplingOutcome.Completed("已执行成功"), result)
        assertEquals(listOf(1), attempts)
        assertEquals(2, seen.size)
        assertEquals(original, seen.first())
        assertEquals(original, seen.last().dropLast(1))
        assertEquals(1, original.size)
        val repair = seen.last().last().getValue("content").jsonPrimitive.content
        assertTrue(repair.contains("工具尚未执行"))
        assertTrue(repair.contains("沿用已有工具回执，从尚未执行的动作继续"))
    }

    @Test fun actualMiniMaxToolJsonErrorFeedsSpecificCauseBackToTheModel() = runTest {
        val error = LocalModelException(
            code = "ANTHROPIC_TOOL_ARGUMENTS_INVALID",
            message = "工具 tool_call/bash 的 arguments 字段缺少值",
            retryable = false,
            admissionState = LocalModelAdmissionState.ADMITTED,
            continuationEligible = true,
            providerParam = "tool_call/bash",
            toolInputIssue = "missing_arguments_value",
        )
        val seen = mutableListOf<List<JsonObject>>()
        val response = sampleWithToolArgumentsRepair(
            revisions = MutableStateFlow(0L),
            observedRevision = 0L,
            originalMessages = original,
            canRepair = { true },
            onRepair = {},
        ) { history ->
            seen += history
            if (seen.size == 1) throw error
            "工具已修正"
        }
        assertEquals(LocalWorkSamplingOutcome.Completed("工具已修正"), response)
        val correction = seen.last().last().getValue("content").jsonPrimitive.content
        assertTrue(correction.contains("missing_arguments_value"))
        assertTrue(correction.contains("tool_call/bash"))
        assertTrue(correction.contains("\"command\""))
        assertTrue(correction.contains("tool_search"))
        assertEquals(original, seen.last().dropLast(1))
    }

    @Test fun malformedSseFrameAlsoContinuesInSameStepWithPreciseRecoveryCause() = runTest {
        val failure = LocalModelException(
            code = "ANTHROPIC_PROTOCOL_ERROR",
            message = "Anthropic SSE 包含无法解析的数据帧",
            retryable = false,
            admissionState = LocalModelAdmissionState.ADMITTED,
            continuationEligible = true,
        )
        var calls = 0
        var instruction = ""
        val outcome = sampleWithToolArgumentsRepair(
            revisions = MutableStateFlow(0L),
            observedRevision = 0L,
            originalMessages = original,
            canRepair = { true },
            onRepair = {},
        ) { messages ->
            calls++
            if (calls == 1) throw failure
            instruction = messages.last().getValue("content").jsonPrimitive.content
            "恢复完成"
        }
        assertEquals(LocalWorkSamplingOutcome.Completed("恢复完成"), outcome)
        assertEquals(2, calls)
        assertTrue(instruction.contains("ANTHROPIC_PROTOCOL_ERROR"))
        assertTrue(instruction.contains("SSE"))
        assertTrue(instruction.contains("根据错误原因重新生成本轮响应"))
        assertTrue(!instruction.contains("tool_search"))
    }

    @Test fun allAdmittedProviderProtocolErrorsProvideInStepCorrection() = runTest {
        for (code in listOf("MODEL_STREAM_PROTOCOL", "MODEL_RESPONSE_PROTOCOL", "RESPONSES_PROTOCOL_ERROR", "MODEL_EMPTY_RESPONSE")) {
            var samples = 0
            val failure = LocalModelException(
                code = code,
                message = "服务商工具参数不是完整 JSON",
                retryable = false,
                admissionState = LocalModelAdmissionState.ADMITTED,
                continuationEligible = true,
            )
            var correction = ""
            val result = sampleWithToolArgumentsRepair(
                revisions = MutableStateFlow(0L),
                observedRevision = 0L,
                originalMessages = original,
                canRepair = { true },
                onRepair = {},
            ) { request ->
                samples++
                if (samples == 1) throw failure
                correction = request.last().getValue("content").jsonPrimitive.content
                "已纠正"
            }
            assertEquals(LocalWorkSamplingOutcome.Completed("已纠正"), result)
            assertEquals(2, samples)
            assertTrue(correction.contains(code))
            assertTrue(correction.contains("根据错误原因重新生成本轮响应"))
            assertTrue(!correction.contains("tool_search"))
        }
    }

    @Test fun preAdmissionErrorsDoNotCreateCorrectiveModelRequest() = runTest {
        var requests = 0
        val error = LocalModelException(
            code = "MODEL_STREAM_PROTOCOL",
            message = "本地输入校验失败",
            retryable = false,
            admissionState = LocalModelAdmissionState.NOT_SENT,
            continuationEligible = false,
        )
        val rejected = runCatching {
            sampleWithToolArgumentsRepair(
                revisions = MutableStateFlow(0L), observedRevision = 0L,
                originalMessages = original, canRepair = { true }, onRepair = {},
            ) {
                requests++
                throw error
            }
        }.exceptionOrNull()
        assertEquals(error, rejected)
        assertEquals(1, requests)
    }

    @Test fun boundedRepairDoesNotRepeatIndefinitely() = runTest {
        val attempts = mutableListOf<Int>()
        var requests = 0
        val failure = runCatching {
            sampleWithToolArgumentsRepair(
                revisions = MutableStateFlow(0L),
                observedRevision = 0L,
                originalMessages = original,
                canRepair = { true },
                onRepair = attempts::add,
            ) {
                requests++
                throw invalidArguments()
            }
        }.exceptionOrNull()
        assertEquals("ANTHROPIC_TOOL_ARGUMENTS_INVALID", (failure as? LocalModelException)?.code)
        assertEquals(3, requests)
        assertEquals(listOf(1, 2), attempts)
    }

    @Test fun toolSideEffectFencePreventsAnyModelResampling() = runTest {
        var requests = 0
        var recovered = false
        val failure = runCatching {
            sampleWithToolArgumentsRepair(
                revisions = MutableStateFlow(0L),
                observedRevision = 0L,
                originalMessages = original,
                canRepair = { false },
                onRepair = { recovered = true },
            ) {
                requests++
                throw invalidArguments()
            }
        }.exceptionOrNull()
        assertEquals("ANTHROPIC_TOOL_ARGUMENTS_INVALID", (failure as? LocalModelException)?.code)
        assertEquals(1, requests)
        assertFalse(recovered)
    }
}
