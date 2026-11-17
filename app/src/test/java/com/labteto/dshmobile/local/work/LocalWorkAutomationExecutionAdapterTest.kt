package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.send.LocalSendResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkAutomationExecutionAdapterTest {
    @Test
    fun forwardsCompleteExecutionRequestAndTerminalResult() = runTest {
        var captured: LocalWorkExecutionRequest? = null
        var recoveryPrepared = false
        val execution = object : LocalWorkExecutionPort {
            override fun send(
                text: String,
                attachments: List<LocalImportedAttachment>,
            ): LocalSendResult = error("unused")

            override fun editAndResendUserMessage(
                messageId: String,
                replacement: String,
            ): com.labteto.dshmobile.local.session.LocalUserMessageEditResult =
                com.labteto.dshmobile.local.session.LocalUserMessageEditResult.UNAVAILABLE

            override fun queuedUserInputs() = emptyList<com.labteto.dshmobile.local.agent.LocalQueuedUserPreview>()
            override fun removeQueuedUserInput(messageId: String) = false
            override fun reorderQueuedUserInputs(orderedIds: List<String>) = false
            override fun regenerateReply(messageId: String): Boolean = false

            override suspend fun prepareSession(
                text: String,
                preferredSessionId: String?,
                recoverInterrupted: Boolean,
            ): String {
                recoveryPrepared = recoverInterrupted
                return preferredSessionId ?: "prepared"
            }

            override suspend fun execute(request: LocalWorkExecutionRequest): LocalWorkExecutionResult {
                captured = request
                return LocalWorkExecutionResult(
                    sessionId = "session-result",
                    output = "result",
                    status = LocalWorkExecutionStatus.BLOCKED,
                    detail = "detail",
                )
            }

            override fun cancel(sessionId: String): Boolean = false

            override suspend fun cancelAndJoin(sessionId: String): Boolean = false
        }
        val adapter = LocalWorkAutomationExecutionAdapter(execution)

        assertEquals(
            "prepared-id",
            adapter.prepareSession("准备任务", "prepared-id", recoverInterrupted = true),
        )
        assertTrue(recoveryPrepared)
        val result = adapter.run(
            text = "执行任务",
            preferredSessionId = "target-id",
            timeoutMillis = 654_321L,
            recoverInterrupted = true,
        )

        val request = requireNotNull(captured)
        assertEquals("执行任务", request.text)
        assertEquals("target-id", request.targetSessionId)
        assertEquals(654_321L, request.timeoutMillis)
        assertTrue(request.recoverInterrupted)
        assertEquals("session-result", result.sessionId)
        assertEquals("result", result.output)
        assertEquals(LocalWorkAutomationStatus.BLOCKED, result.status)
        assertEquals("detail", result.detail)
    }
}
