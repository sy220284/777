package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalModelReplyValidationTest {
    @Test
    fun emptyCompletedResponseSurfacesAnErrorWithoutReplay() {
        listOf(null, "", " \n ").forEach { content ->
            val error = assertThrows(LocalModelException::class.java) {
                validateUsableModelReply(reply(content).copy(requestId = "request-1"))
            }
            assertEquals("MODEL_EMPTY_RESPONSE", error.code)
            assertEquals("request-1", error.requestId)
            assertFalse(error.retryable)
        }
    }

    @Test
    fun reasoningAloneCannotFinishButToolOnlyAndExplicitGroupSilenceRemainValid() {
        assertThrows(LocalModelException::class.java) {
            validateUsableModelReply(reply(null).copy(reasoning = "thinking"))
        }
        validateUsableModelReply(reply(null).copy(toolCalls = listOf(LocalToolCall("a", "read", buildJsonObject {}, "{}"))))
        validateUsableModelReply(reply("[SILENCE]"))
        validateUsableModelReply(reply("answer"))
    }

    private fun reply(content: String?) = LocalModelReply(
        message = buildJsonObject {}, content = content, reasoning = null, toolCalls = emptyList(),
    )
    @Test
    fun emptyNoncompleteRepliesReachUsageOwnerButCompletedStillFails() {
        LocalModelCompletionReason.entries.filter { it != LocalModelCompletionReason.COMPLETED }.forEach { reason ->
            val partial = reply(null).copy(completionReason = reason, rawStopReason = "provider-reason",
                usage = DeepSeekTokenUsage(promptTokens = 3, completionTokens = 8, reported = true))
            validateUsableModelReply(partial)
            assertEquals(8L, agentReportedModelUsage(partial.usage)?.completionTokens)
            assertEquals(reason, partial.completionReason)
        }
    }

    @Test
    fun completeResultConsumersRejectPartialEvenWhenBodyIsValidStructuredJson() {
        LocalModelCompletionReason.entries.filter { it != LocalModelCompletionReason.COMPLETED }.forEach { reason ->
            val partial = reply("{\"name\":\"合法但未完成的人物或文档\"}").copy(
                completionReason = reason, rawStopReason = "provider-stop", requestId = "partial-request",
                usage = DeepSeekTokenUsage(completionTokens = 9, reported = true))
            val reported = agentReportedModelUsage(partial.usage)
            val failure = assertThrows(LocalModelException::class.java) { requireCompletedModelReply(partial) }
            assertEquals(9L, reported?.completionTokens)
            assertEquals("MODEL_${reason.name}", failure.code)
            assertEquals("partial-request", failure.requestId)
            assertFalse(failure.retryable)
        }
        requireCompletedModelReply(reply("{\"name\":\"完整结果\"}"))
    }

}
