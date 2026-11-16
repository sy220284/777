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
        assertTrue(repair.contains("不要重复"))
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
