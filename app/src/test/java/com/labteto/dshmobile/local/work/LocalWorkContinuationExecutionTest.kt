package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInputKind
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelAdmissionState
import com.labteto.dshmobile.local.runtime.LocalAgentRunHandle
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkContinuationExecutionTest {
    @get:Rule val files = TemporaryFolder()

    private fun withBinding(block: (LocalWorkRunBinding) -> Unit) {
        val log = LocalSessionEventLog(File(files.newFolder(), "events.jsonl"), Json)
        val binding = LocalWorkRunBinding(
            sessionId = "test-session",
            initialState = LocalHarnessState(
                sessionId = "test-session",
                usageMode = LocalUsageMode.WORK,
            ).toLocalWorkRunState(),
            sessionBase = LocalHarnessSession(
                id = "test-session",
                usageMode = LocalUsageMode.WORK,
            ),
            runHandle = LocalAgentRunHandle(
                initialSessionId = "test-session", maxPendingInputs = 8,
            ),
            eventLog = log,
        )
        try {
            block(binding)
        } finally {
            log.close()
        }
    }

    private fun interruptedError() = LocalModelException(
        code = "ANTHROPIC_STREAM_INTERRUPTED_AFTER_ADMISSION",
        message = "连接在模型响应中断开",
        retryable = false,
        admissionState = LocalModelAdmissionState.ADMITTED,
        continuationEligible = true,
    )

    @Test fun recoverableModelFailureEnqueuesExactlyOneDurableContinuation() = withBinding { binding ->
        val checkpoint = binding.eventLog.append("turn/checkpoint", buildJsonObject {
            put("kind", "stream")
        })
        assertTrue(queueAutomaticWorkContinuation(
            binding, "run-1", interruptedError(), sourceMessageId = "user-1",
        ))
        val queue = binding.runHandle.pendingInputs.snapshot()
        assertEquals(1, queue.size)
        assertEquals(QueuedAgentInputKind.WORK_CONTINUATION, queue.single().kind)
        val continuation = requireNotNull(queue.single().continuation)
        assertEquals("run-1", continuation.sourceRunId)
        assertEquals(checkpoint.sequence, continuation.checkpointSequence)
        assertEquals("user-1", continuation.sourceMessageId)
        assertEquals(1, binding.automaticContinuationCount)
        assertNotNull(binding.eventLog.latest("turn/continuation-queued"))
        assertFalse(queueAutomaticWorkContinuation(
            binding, "run-1", interruptedError(), sourceMessageId = "user-1",
        ))
        assertEquals(1, binding.runHandle.pendingInputs.size())
    }

    @Test fun nonContinuableFailureLeavesInboxUnchanged() = withBinding { binding ->
        val denied = LocalModelException(
            code = "AUTH_REJECTED",
            message = "授权失败",
            retryable = false,
            continuationEligible = false,
        )
        assertFalse(queueAutomaticWorkContinuation(binding, "run-1", denied))
        assertEquals(0, binding.runHandle.pendingInputs.size())
        assertEquals(0, binding.automaticContinuationCount)
        assertEquals(null, binding.eventLog.latest("turn/continuation-queued"))
    }
}
