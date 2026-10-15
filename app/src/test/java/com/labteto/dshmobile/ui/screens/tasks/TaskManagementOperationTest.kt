package com.labteto.dshmobile.ui.screens.tasks

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TaskManagementOperationTest {
    @Test fun failedAcknowledgementReadsCurrentFactsWithoutReplayingMutation() = runTest {
        var writes = 0
        var reads = 0
        val result = runTaskManagementOperation({ writes++; throw IOException("ack lost after commit") }, { reads++; emptyList() })
        assertTrue(result.failed)
        assertFalse(result.accepted)
        assertEquals(1, writes)
        assertEquals(1, reads)
        assertNotNull(result.tasks)
    }
    @Test fun rejectedCommandIsDifferentFromFailureAndReadFailureDoesNotInventEmptyTasks() = runTest {
        val rejected = runTaskManagementOperation({ false }, { emptyList() })
        assertFalse(rejected.accepted)
        assertFalse(rejected.failed)
        val unknown = runTaskManagementOperation({ true }, { throw IOException("store unavailable") })
        assertTrue(unknown.accepted)
        assertTrue(unknown.failed)
        assertNull(unknown.tasks)
    }
    @Test fun cancellationPropagatesWithoutRunningAnotherAction() = runTest {
        var reads = 0
        try {
            runTaskManagementOperation({ throw CancellationException("cancelled") }, { reads++; emptyList() })
            fail("cancellation must propagate")
        } catch (_: CancellationException) { assertEquals(0, reads) }
    }
}
