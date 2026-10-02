package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentProgressTrackerTest {
    @Test
    fun repeatedToolEvidenceCannotBuyAnotherExtension() {
        val tracker = LocalAgentProgressTracker()
        val call = AgentToolCall(
            id = "call-1",
            name = "read",
            arguments = buildJsonObject { put("path", "README.md") },
            rawArguments = """{"path":"README.md"}""",
        )

        assertFalse(tracker.claimExtensionProgress())

        tracker.recordToolResult(call, "result", isError = false)
        assertTrue(tracker.claimExtensionProgress())
        assertFalse(tracker.claimExtensionProgress())

        tracker.recordToolResult(call.copy(id = "call-2"), "result", isError = false)
        assertFalse(tracker.claimExtensionProgress())

        tracker.recordToolResult(call.copy(id = "call-3"), "changed result", isError = false)
        assertTrue(tracker.claimExtensionProgress())
    }

    @Test
    fun oldEvidenceCannotBecomeNewAgainAfterManyUniqueSteps() {
        val tracker = LocalAgentProgressTracker()
        val base = AgentToolCall(
            id = "seed",
            name = "read",
            arguments = buildJsonObject { put("path", "README.md") },
        )
        tracker.recordToolResult(base, "result-0", isError = false)
        assertTrue(tracker.claimExtensionProgress())

        repeat(80) { index ->
            tracker.recordToolResult(base.copy(id = "call-$index"), "result-${index + 1}", isError = false)
        }
        assertTrue(tracker.claimExtensionProgress())

        tracker.recordToolResult(base.copy(id = "replay"), "result-0", isError = false)
        assertFalse(tracker.claimExtensionProgress())
    }

    @Test
    fun repeatedTextOnlyReplyCountsOnceAndToolCallingReplyDoesNotCount() {
        val tracker = LocalAgentProgressTracker()

        tracker.recordAssistant("阶段结论", toolCallCount = 0)
        assertTrue(tracker.claimExtensionProgress())

        tracker.recordAssistant("阶段结论", toolCallCount = 0)
        assertFalse(tracker.claimExtensionProgress())

        tracker.recordAssistant("准备调用工具", toolCallCount = 1)
        assertFalse(tracker.claimExtensionProgress())
    }
}
