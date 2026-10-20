package com.labteto.dshmobile.local.session

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalSessionDeletionCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun startupFinishesOnlyCommittedDeletionAfterInterruptedCleanup() {
        val sessions = temporary.newFolder("sessions")
        val snapshot = File(sessions, "deleted.json").apply { writeText("important snapshot") }
        val event = File(sessions, "deleted.events.jsonl").apply { writeText("old events") }
        val writer = File(sessions, "deleted.events.jsonl.writer.lock").apply { writeText("") }
        val checkpoint = File(sessions, "deleted.checkpoint-v1.json").apply { writeText("old checkpoint") }
        val abandonedWrite = File(sessions, "deleted.json.tmp").apply { writeText("partially written") }
        val summaryDir = File(sessions, ".summaries").apply { mkdirs() }
        val summary = File(summaryDir, "deleted.summary").apply { writeText("old summary") }
        val unrelated = File(sessions, "other.events.jsonl").apply { writeText("untouched") }
        var toolCleanupAttempts = 0
        var eventClearAttempts = 0
        var throwOnce = true
        val cleanup = LocalSessionDeletionCleanup(sessions,
            clearEvents = { eventClearAttempts++ },
            clearToolOutputs = {
                toolCleanupAttempts++
                if (throwOnce) {
                    throwOnce = false
                    error("simulated filesystem failure")
                }
            },
        )
        cleanup.markIntent("deleted")
        assertTrue(File(sessions, ".pending-deletions/deleted.pending").exists())
        assertTrue(cleanup.recoverPending().isEmpty())
        assertEquals(0, eventClearAttempts)
        assertTrue(snapshot.exists())

        assertTrue(snapshot.delete())
        val failed = cleanup.recoverPending()
        assertEquals(1, failed.size)
        assertTrue(event.exists())
        assertTrue(File(sessions, ".pending-deletions/deleted.pending").exists())

        val restarted = LocalSessionDeletionCleanup(sessions,
            clearEvents = { eventClearAttempts++ },
            clearToolOutputs = { toolCleanupAttempts++ },
        )
        assertTrue(restarted.recoverPending().isEmpty())
        assertFalse(event.exists())
        assertFalse(writer.exists())
        assertFalse(checkpoint.exists())
        assertFalse(abandonedWrite.exists())
        assertFalse(summary.exists())
        assertFalse(File(sessions, ".pending-deletions/deleted.pending").exists())
        assertTrue(unrelated.exists())
        assertEquals(2, toolCleanupAttempts)
        assertEquals(2, eventClearAttempts)
    }

    @Test
    fun successfulCleanupClearsMarkerSoStartupDoesNotRepeatDeletion() {
        val sessions = temporary.newFolder("complete")
        var calls = 0
        val cleanup = LocalSessionDeletionCleanup(sessions,
            clearEvents = { calls++ },
            clearToolOutputs = { calls++ },
        )
        cleanup.markIntent("finished")
        cleanup.cleanupCommitted("finished")
        assertEquals(2, calls)
        assertTrue(cleanup.recoverPending().isEmpty())
        assertEquals(2, calls)
    }
}
