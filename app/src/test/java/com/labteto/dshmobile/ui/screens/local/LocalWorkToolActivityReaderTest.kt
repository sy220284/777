package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test

class LocalWorkToolActivityReaderTest {
    @Test fun committedRevisionsWakeReadersWithoutTimerPolling() = runBlocking {
        val revisions = MutableStateFlow(0L)
        val observed = Channel<Long>(Channel.UNLIMITED)
        var sequence = 10L
        var reads = 0
        val reader = LocalWorkToolActivityReader(
            sequence = { sequence },
            activities = { reads++; emptyList() },
        )
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            observeWorkToolActivityChanges(revisions, reader) { result ->
                if (result is LocalWorkToolActivityRead.Available) observed.send(sequence)
            }
        }
        try {
            withTimeout(5_000) { assertEquals(10L, observed.receive()) }
            assertEquals(1, reads)
            sequence = 11L
            revisions.value = 1L
            withTimeout(5_000) { assertEquals(11L, observed.receive()) }
            assertEquals(2, reads)
            // There is no periodic wake-up between EventLog commits.
        } finally {
            collector.cancelAndJoin()
            observed.close()
        }
    }

    @Test fun workToolReaderWaitsForStartupRestoreAndValidSession() {
        assertFalse(shouldReadWorkToolStatus(LocalUsageMode.WORK, loading = true, sessionId = "fresh-session"))
        assertFalse(shouldReadWorkToolStatus(LocalUsageMode.WORK, loading = false, sessionId = ""))
        assertFalse(shouldReadWorkToolStatus(LocalUsageMode.CHAT, loading = false, sessionId = "fresh-session"))
        assertTrue(shouldReadWorkToolStatus(LocalUsageMode.WORK, loading = false, sessionId = "fresh-session"))
    }

    @Test fun automaticRetryBacksOffWithoutBusyPolling() {
        assertEquals(750L, workToolReadRetryDelayMs(1))
        assertEquals(1_500L, workToolReadRetryDelayMs(2))
        assertEquals(3_000L, workToolReadRetryDelayMs(3))
        assertEquals(6_000L, workToolReadRetryDelayMs(4))
        assertEquals(12_000L, workToolReadRetryDelayMs(5))
        assertEquals(20_000L, workToolReadRetryDelayMs(6))
        assertEquals(20_000L, workToolReadRetryDelayMs(100))
    }

    @Test fun transientFailuresAndEmptyWelcomePageStayQuiet() {
        assertFalse(shouldShowWorkToolReadFailure(1, true))
        assertFalse(shouldShowWorkToolReadFailure(2, true))
        assertFalse(shouldShowWorkToolReadFailure(10, false))
        assertTrue(shouldShowWorkToolReadFailure(3, true))
        assertFalse(shouldShowWorkToolReadFailure(0, true))
    }

    @Test fun failedReadCanRecoverOnSameRevisionWithoutReplaying() {
        var unavailable = true
        var reads = 0
        val row = LocalToolActivityUiItem("call", "read", LocalToolUiPhase.RUNNING)
        val reader = LocalWorkToolActivityReader(
            sequence = { 10L },
            activities = {
                reads++
                if (unavailable) throw IOException("event log unavailable")
                listOf(row)
            },
        )
        assertTrue(reader.read() is LocalWorkToolActivityRead.Failed)
        unavailable = false
        assertEquals(listOf(row), (reader.read() as LocalWorkToolActivityRead.Available).activities)
        assertNull(reader.read())
        assertEquals(2, reads)
    }

    @Test fun sequenceFailureBecomesExplicitFailureAndSessionTransitionClearsLiveRows() {
        val failed = LocalWorkToolActivityReader({ throw IOException("unreadable") }, { emptyList() })
        assertTrue(failed.read() is LocalWorkToolActivityRead.Failed)
        val switched = LocalWorkToolActivityReader({ -1L }, { error("inactive session must not be read") })
        assertEquals(emptyList<LocalToolActivityUiItem>(),
            (switched.read() as LocalWorkToolActivityRead.Available).activities)
    }

    @Test fun cancellationIsNeverTurnedIntoAnErrorBanner() {
        val reader = LocalWorkToolActivityReader({ throw CancellationException("closed") }, { emptyList() })
        try {
            reader.read()
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
