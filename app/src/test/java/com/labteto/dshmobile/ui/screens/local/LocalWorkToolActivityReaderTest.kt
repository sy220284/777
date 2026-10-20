package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LocalWorkToolActivityReaderTest {
    @Test fun failedReadDoesNotAdvanceTheCursorAndManualRetryReadsSameRevision() {
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
