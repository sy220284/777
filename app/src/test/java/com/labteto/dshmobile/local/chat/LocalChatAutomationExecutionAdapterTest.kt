package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.send.LocalSendResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatAutomationExecutionAdapterTest {
    @Test
    fun forwardsCompleteAutomationPolicyAndTerminalResult() = runTest {
        var captured: LocalChatExecutionRequest? = null
        val execution = object : LocalChatExecutionPort {
            override fun send(
                text: String,
                attachments: List<LocalImportedAttachment>,
            ): LocalSendResult = error("unused")

            override fun editAndResendUserMessage(
                messageId: String,
                replacement: String,
            ): LocalChatUserEditResult = error("unused")

            override fun regenerateReply(messageId: String): Boolean = false

            override suspend fun execute(request: LocalChatExecutionRequest): LocalChatExecutionResult {
                captured = request
                return LocalChatExecutionResult(
                    sessionId = "session-result",
                    output = "result",
                    status = LocalChatExecutionStatus.SKIPPED,
                    detail = "detail",
                    nextRunAtHint = 999L,
                    waitingForUserReply = true,
                )
            }

            override fun cancel(sessionId: String): Boolean = false

            override suspend fun cancelAndJoin(sessionId: String): Boolean = false
        }
        val adapter = LocalChatAutomationExecutionAdapter(execution)
        val policy = LocalChatAutomationPolicy(
            quietHoursEnabled = true,
            quietStartHour = 21,
            quietStartMinute = 17,
            quietEndHour = 8,
            quietEndMinute = 41,
            proactiveMinGapMinutes = 777L,
            proactiveMaxUnanswered = 4,
            minimumSilenceMinutes = 888L,
            silenceReferenceAt = 1_234L,
            bypassProactivePolicy = true,
        )

        val result = adapter.run(
            instruction = "主动互动",
            targetSessionId = "session-target",
            timeoutMillis = 123_456L,
            recoverInterrupted = true,
            recoveryStartedAt = 456L,
            policy = policy,
        )

        val request = requireNotNull(captured)
        assertEquals("主动互动", request.instruction)
        assertEquals("session-target", request.targetSessionId)
        assertEquals(123_456L, request.timeoutMillis)
        assertTrue(request.recoverInterrupted)
        assertEquals(456L, request.recoveryStartedAt)
        assertEquals(policy, request.automationPolicy)
        assertEquals("session-result", result.sessionId)
        assertEquals("result", result.output)
        assertEquals(LocalChatAutomationStatus.SKIPPED, result.status)
        assertEquals("detail", result.detail)
        assertEquals(999L, result.nextRunAtHint)
        assertTrue(result.waitingForUserReply)
    }
}
