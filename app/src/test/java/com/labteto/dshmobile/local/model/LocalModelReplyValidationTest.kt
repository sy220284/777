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
}
