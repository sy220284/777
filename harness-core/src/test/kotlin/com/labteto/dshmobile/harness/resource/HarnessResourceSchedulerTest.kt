package com.labteto.dshmobile.harness.resource

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessResourceSchedulerTest {
    @Test
    fun blocksSecondModelRequestUntilPermitIsReleased() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduler = HarnessResourceScheduler(
            HarnessResourceBudget(maxModelRequests = 1, maxAgents = 2),
        )

        val first = async {
            scheduler.withResource(HarnessResourceKind.MODEL_REQUEST) {
                entered.complete(Unit)
                release.await()
                "first"
            }
        }
        entered.await()

        val secondEntered = CompletableDeferred<Unit>()
        val second = async {
            scheduler.withResource(HarnessResourceKind.MODEL_REQUEST) {
                secondEntered.complete(Unit)
                "second"
            }
        }

        assertTrue(!secondEntered.isCompleted)
        assertEquals(HarnessResourcePressure.HIGH, scheduler.snapshot().pressure)

        release.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("second", second.await())
    }

    @Test
    fun reportsPressureAndAvailableAgentSlots() = runTest {
        val scheduler = HarnessResourceScheduler(
            HarnessResourceBudget(maxModelRequests = 4, maxAgents = 2),
        )

        scheduler.withResource(HarnessResourceKind.AGENT) {
            val snapshot = scheduler.snapshot()
            assertEquals(1, snapshot.activeAgents)
            assertEquals(1, snapshot.availableAgentSlots)
            assertEquals(HarnessResourcePressure.LOW, snapshot.pressure)
        }
    }
}
