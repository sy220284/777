package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LocalWorkToolActivityReaderTest {
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

    @Test fun failedReadRetriesAutomaticallyWithoutANewEventSequence() {
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
