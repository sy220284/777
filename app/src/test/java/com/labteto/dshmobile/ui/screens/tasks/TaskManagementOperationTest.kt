package com.labteto.dshmobile.ui.screens.tasks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskManagementOperationTest {
    @Test fun failedAcknowledgementReadsCurrentFactsWithoutReplayingMutation() = runBlocking {
        var mutationCalls = 0
        var observations = 0
        val result = runTaskManagementOperation(
            operation = {
                mutationCalls++
                throw IllegalStateException("remote acknowledgement lost after write")
            },
            readTasks = {
                observations++
                emptyList()
            },
        )
        assertEquals(1, mutationCalls)
        assertEquals(1, observations)
        assertFalse(result.accepted)
        assertTrue(result.failed)
        assertEquals(emptyList<com.labteto.dshmobile.automation.AutomationTask>(), result.tasks)
    }

    @Test fun userCancellationIsPropagatedWithoutNewMutation() = runBlocking {
        var observed = false
        try {
            runTaskManagementOperation(
                operation = { throw CancellationException("user cancelled") },
                readTasks = { observed = true; emptyList() },
            )
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(observed)
        }
    }
}
