package com.labteto.dshmobile.harness.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecoveryToolCancellationSemanticsTest {
    @Test fun notStartedToolCanBeReplayedWithoutPossibleSideEffect() {
        val event = SessionRecovery.interruptedToolResult(
            "unstarted", "write", 1, started = false,
        )
        assertEquals(SessionRecovery.TOOL_NOT_STARTED, event.code)
        assertTrue(event.modelContent.contains("retryable"))
        assertFalse(event.modelContent.contains("TOOL_OUTCOME_UNKNOWN"))
    }

    @Test fun verifiedReadStartedButInterruptedIsNotMarkedCompleted() {
        val event = SessionRecovery.interruptedToolResult(
            "read-call", "read", 1, started = true, verifiedReadOnly = true,
        )
        assertEquals(SessionRecovery.TOOL_READ_RESULT_UNKNOWN, event.code)
        assertTrue(event.content.contains("没有完整结果"))
        assertTrue(event.modelContent.contains("none"))
    }

    @Test fun startedWriterStillRequiresHumanStateVerification() {
        val event = SessionRecovery.interruptedToolResult(
            "write-call", "write", 2, started = true,
        )
        assertEquals(SessionRecovery.TOOL_OUTCOME_UNKNOWN, event.code)
        assertTrue(event.content.contains("副作用"))
        assertTrue(event.modelContent.contains("possible"))
    }
}
