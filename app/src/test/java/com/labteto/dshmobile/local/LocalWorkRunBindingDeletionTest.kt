package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkRunBindingDeletionTest {
    @Test
    fun deletingSessionCancelsJoinsAndDetachesBoundWorkRun() = runTest {
        val root = createTempDir(prefix = "work-run-delete-")
        try {
            val binding = LocalWorkRunBinding(
                sessionId = "work-session",
                initialState = LocalHarnessState(
                    sessionId = "work-session",
                    usageMode = LocalUsageMode.WORK,
                    running = true,
                    queuedInputCount = 1,
                ),
                initialHistory = emptyList(),
                eventLog = LocalSessionEventLog(
                    File(root, "session.events.jsonl"),
                    Json { ignoreUnknownKeys = true },
                ),
                initialTranscriptProjectionCursor = -1L,
                maxPendingInputs = 4,
                pruneToolResult = { it },
            )
            assertTrue(
                binding.pendingInputs.offer(
                    QueuedAgentInput(content = "queued", id = "queued-1"),
                ),
            )

            val started = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            binding.job = backgroundScope.launch {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    finished.complete(Unit)
                }
            }
            binding.mirrorJob = backgroundScope.launch { awaitCancellation() }

            val active = ConcurrentHashMap<String, LocalWorkRunBinding>().apply {
                put(binding.sessionId, binding)
            }
            started.await()

            cancelWorkRunsForDeletedSessions(
                activeRuns = active,
                sessionIds = setOf(binding.sessionId),
                runStateLock = Any(),
            )

            assertTrue(finished.isCompleted)
            assertFalse(active.containsKey(binding.sessionId))
            assertEquals(0, binding.pendingInputs.size())
            assertFalse(binding.state.value.running)
            assertEquals(0, binding.state.value.queuedInputCount)
            assertNull(binding.job)
            assertNull(binding.mirrorJob)
        } finally {
            root.deleteRecursively()
        }
    }
}
