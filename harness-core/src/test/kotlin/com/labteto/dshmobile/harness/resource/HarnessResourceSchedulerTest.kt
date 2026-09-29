package com.labteto.dshmobile.harness.resource

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertFalse(secondEntered.isCompleted)
        assertEquals(HarnessResourcePressure.HIGH, scheduler.snapshot().pressure)

        release.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("second", second.await())
    }

    @Test
    fun longLivedLeaseCountsUntilExplicitClose() = runTest {
        val scheduler = HarnessResourceScheduler(
            HarnessResourceBudget(
                maxModelRequests = 2,
                maxAgents = 2,
                maxTerminals = 1,
                maxVirtualDisplays = 1,
                maxLanguageServers = 1,
            ),
        )

        val terminal = scheduler.acquire(HarnessResourceKind.TERMINAL, "term-1")
        assertEquals(1, scheduler.snapshot().activeTerminals)
        assertEquals("term-1", scheduler.snapshot().leases.single().owner)

        val secondEntered = CompletableDeferred<Unit>()
        val second = async {
            scheduler.withResource(HarnessResourceKind.TERMINAL, "term-2") {
                secondEntered.complete(Unit)
            }
        }
        assertFalse(secondEntered.isCompleted)

        terminal.close()
        second.await()
        assertEquals(0, scheduler.snapshot().activeTerminals)
    }

    @Test
    fun reportsPressureAcrossAllResourceClasses() = runTest {
        val scheduler = HarnessResourceScheduler(
            HarnessResourceBudget(
                maxModelRequests = 4,
                maxAgents = 2,
                maxTerminals = 3,
                maxVirtualDisplays = 1,
                maxLanguageServers = 3,
            ),
        )

        scheduler.withResource(HarnessResourceKind.VIRTUAL_DISPLAY) {
            val snapshot = scheduler.snapshot()
            assertEquals(1, snapshot.activeVirtualDisplays)
            assertEquals(HarnessResourcePressure.HIGH, snapshot.pressure)
        }

        scheduler.withResource(HarnessResourceKind.AGENT) {
            val snapshot = scheduler.snapshot()
            assertEquals(1, snapshot.activeAgents)
            assertEquals(1, snapshot.availableAgentSlots)
            assertEquals(HarnessResourcePressure.LOW, snapshot.pressure)
        }
    }

    @Test
    fun leaseCloseIsIdempotent() = runTest {
        val scheduler = HarnessResourceScheduler(
            HarnessResourceBudget(maxModelRequests = 1, maxAgents = 1),
        )
        val lease = scheduler.acquire(HarnessResourceKind.LANGUAGE_SERVER)
        lease.close()
        lease.close()
        assertEquals(0, scheduler.snapshot().activeLanguageServers)
        assertTrue(scheduler.snapshot().leases.isEmpty())
    }

    @Test
    fun cancelledWaiterStormDoesNotLeakPermitsOrActiveCounters() = runTest {
        val scheduler = HarnessResourceScheduler(
            HarnessResourceBudget(maxModelRequests = 1, maxAgents = 2),
        )
        val held = scheduler.acquire(HarnessResourceKind.MODEL_REQUEST, "holder")
        val entered = mutableSetOf<Int>()

        val waiters = (0 until 64).map { index ->
            launch {
                scheduler.withResource(HarnessResourceKind.MODEL_REQUEST, "waiter-$index") {
                    entered += index
                }
            }
        }
        runCurrent()

        waiters.filterIndexed { index, _ -> index % 2 == 0 }.forEach { it.cancel() }
        waiters.filterIndexed { index, _ -> index % 2 == 0 }.forEach { it.cancelAndJoin() }

        held.close()
        waiters.filterIndexed { index, _ -> index % 2 == 1 }.forEach { it.join() }

        assertEquals((0 until 64).filter { it % 2 == 1 }.toSet(), entered)
        assertEquals(0, scheduler.snapshot().activeModelRequests)
        assertTrue(scheduler.snapshot().leases.isEmpty())
    }

}
