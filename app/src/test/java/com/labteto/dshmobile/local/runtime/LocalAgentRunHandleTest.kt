package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import kotlinx.coroutines.Job
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentRunHandleTest {
    @Test
    fun rebindClearsSessionScopedRuntimeFacts() {
        val handle = LocalAgentRunHandle(
            initialSessionId = "session-a",
            initialHistory = listOf(
                buildJsonObject {
                    put("role", "user")
                    put("content", "old")
                },
            ),
            initialTranscriptProjectionCursor = 42L,
            maxPendingInputs = 4,
        )
        assertTrue(
            handle.pendingInputs.offer(
                QueuedAgentInput(content = "queued", id = "q1"),
            ),
        )
        handle.turnsSinceModelHistoryCheckpoint = 3
        val completedOwner = Job()
        completedOwner.complete()
        val projection = Job()
        handle.job = completedOwner
        handle.projectionJob = projection

        handle.rebindSession("session-b")

        assertEquals("session-b", handle.sessionId)
        assertTrue(handle.modelHistory.snapshot().isEmpty())
        assertEquals(0, handle.pendingInputs.size())
        assertNull(handle.transcriptProjectionCursor)
        assertEquals(0, handle.turnsSinceModelHistoryCheckpoint)
        assertNull(handle.job)
        assertNull(handle.projectionJob)
        assertTrue(projection.isCancelled)
    }

    @Test
    fun rebindRejectsLiveRunOwner() {
        val handle = LocalAgentRunHandle(
            initialSessionId = "session-a",
            maxPendingInputs = 4,
        )
        val owner = Job()
        handle.job = owner

        val failure = runCatching { handle.rebindSession("session-b") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("session-a", handle.sessionId)
        owner.cancel()
    }
}
