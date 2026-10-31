package com.labteto.dshmobile.harness.resource

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
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
    fun fileAndMediaAdmissionUseIndependentBoundedLeases() = runTest {
        val scheduler = HarnessResourceScheduler(
            HarnessResourceBudget(maxModelRequests = 2, maxAgents = 2, maxFileIo = 1, maxMediaDecode = 1),
        )
        val file = scheduler.acquire(HarnessResourceKind.FILE_IO, "import")
        val decode = scheduler.acquire(HarnessResourceKind.MEDIA_DECODE, "pdf")
        assertEquals(1, scheduler.snapshot().activeFileIo)
        assertEquals(1, scheduler.snapshot().activeMediaDecode)
        assertEquals(HarnessResourcePressure.HIGH, scheduler.snapshot().pressure)
        val entered = CompletableDeferred<Unit>()
        val blocked = async {
            scheduler.withResource(HarnessResourceKind.FILE_IO, "second-file") {
                entered.complete(Unit)
            }
        }
        runCurrent()
        assertFalse(entered.isCompleted)
        file.close()
        blocked.await()
        decode.close()
        assertEquals(0, scheduler.snapshot().activeFileIo)
        assertEquals(0, scheduler.snapshot().activeMediaDecode)
        assertTrue(scheduler.snapshot().leases.isEmpty())
    }

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
    fun overlappingCallbacksNeverPublishAnOlderLeaseSnapshotAfterRelease() {
        val observerEntered = CountDownLatch(1)
        val releaseObserver = CountDownLatch(1)
        val blockOnce = AtomicBoolean(false)
        val published = CopyOnWriteArrayList<HarnessResourceSnapshot>()
        val scheduler = HarnessResourceScheduler(
            HarnessResourceBudget(maxModelRequests = 1, maxAgents = 1),
        ) { snapshot ->
            if (snapshot.activeAgents == 1 && snapshot.activeModelRequests == 1 &&
                blockOnce.compareAndSet(false, true)) {
                observerEntered.countDown()
                check(releaseObserver.await(5, TimeUnit.SECONDS))
            }
            published += snapshot
        }
        val model = runBlocking { scheduler.acquire(HarnessResourceKind.MODEL_REQUEST) }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val agentFuture = workers.submit<HarnessResourceLease> {
                runBlocking { scheduler.acquire(HarnessResourceKind.AGENT) }
            }
            assertTrue(observerEntered.await(5, TimeUnit.SECONDS))
            val modelRelease = workers.submit { model.close() }
            // The release updates live state before trying to publish. Its callback
            // must not overtake the still-blocked older callback.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (scheduler.snapshot().activeModelRequests != 0 && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(0, scheduler.snapshot().activeModelRequests)
            assertEquals(1, scheduler.snapshot().activeAgents)
            releaseObserver.countDown()
            val agent = agentFuture.get(5, TimeUnit.SECONDS)
            modelRelease.get(5, TimeUnit.SECONDS)
            val finalSnapshot = scheduler.snapshot()
            assertEquals(finalSnapshot, published.last())
            published.forEach { report ->
                assertEquals(report.activeModelRequests,
                    report.leases.count { it.kind == HarnessResourceKind.MODEL_REQUEST })
                assertEquals(report.activeAgents,
                    report.leases.count { it.kind == HarnessResourceKind.AGENT })
            }
            agent.close()
            assertEquals(0, scheduler.snapshot().activeAgents)
        } finally {
            releaseObserver.countDown()
            model.close()
            workers.shutdownNow()
        }
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


    @Test
    fun cancellationStormAcrossAllResourceKindsLeavesNoLeasesOrCounters() = runTest {
        val scheduler = HarnessResourceScheduler(
            HarnessResourceBudget(
                maxModelRequests = 1,
                maxAgents = 1,
                maxTerminals = 1,
                maxVirtualDisplays = 1,
                maxLanguageServers = 1,
                maxFileIo = 1,
                maxMediaDecode = 1,
            ),
        )
        val kinds = HarnessResourceKind.values().toList()
        val held = kinds.associateWith { kind ->
            scheduler.acquire(kind, "holder-${kind.name.lowercase()}")
        }
        val entered = mutableSetOf<String>()

        val waiters = (0 until 50).map { index ->
            val kind = kinds[index % kinds.size]
            launch {
                scheduler.withResource(kind, "waiter-$index") {
                    entered += "${kind.name}:$index"
                }
            }
        }
        runCurrent()

        waiters.filterIndexed { index, _ -> index % 2 == 0 }.forEach { it.cancel() }
        waiters.filterIndexed { index, _ -> index % 2 == 0 }.forEach { it.cancelAndJoin() }
        held.values.forEach { it.close() }
        waiters.filterIndexed { index, _ -> index % 2 == 1 }.forEach { it.join() }

        val snapshot = scheduler.snapshot()
        assertEquals(25, entered.size)
        assertEquals(0, snapshot.activeModelRequests)
        assertEquals(0, snapshot.activeAgents)
        assertEquals(0, snapshot.activeTerminals)
        assertEquals(0, snapshot.activeVirtualDisplays)
        assertEquals(0, snapshot.activeLanguageServers)
        assertTrue(snapshot.leases.isEmpty())
        assertEquals(HarnessResourcePressure.LOW, snapshot.pressure)
    }

    @Test
    fun liveCapacityReductionWaitsForExistingLeasesWithoutRevokingThem() = runTest {
        val scheduler = HarnessResourceScheduler(
            HarnessResourceBudget(maxModelRequests = 3, maxAgents = 3),
        )
        val leases = (1..3).map { scheduler.acquire(HarnessResourceKind.MODEL_REQUEST) }
        scheduler.updateBudget(HarnessResourceBudget(maxModelRequests = 1, maxAgents = 1))
        assertEquals(3, scheduler.snapshot().activeModelRequests)
        assertEquals(1, scheduler.snapshot().budget.maxModelRequests)

        val next = async { scheduler.acquire(HarnessResourceKind.MODEL_REQUEST) }
        runCurrent()
        leases[0].close()
        leases[1].close()
        runCurrent()
        assertFalse(next.isCompleted)
        leases[2].close()
        val admitted = next.await()
        assertEquals(1, scheduler.snapshot().activeModelRequests)
        admitted.close()
        assertTrue(scheduler.snapshot().leases.isEmpty())
    }

    @Test
    fun liveCapacityIncreaseWakesWaitersWithoutReleasingAnActiveLease() = runTest {
        val scheduler = HarnessResourceScheduler(
            HarnessResourceBudget(maxModelRequests = 1, maxAgents = 1),
        )
        val held = scheduler.acquire(HarnessResourceKind.AGENT)
        val waiting = (1..2).map { async { scheduler.acquire(HarnessResourceKind.AGENT) } }
        runCurrent()
        assertTrue(waiting.none { it.isCompleted })
        scheduler.updateBudget(HarnessResourceBudget(maxModelRequests = 1, maxAgents = 3))
        val granted = waiting.map { it.await() }
        assertEquals(3, scheduler.snapshot().activeAgents)
        granted.forEach { it.close() }
        held.close()
        assertEquals(0, scheduler.snapshot().activeAgents)
    }

    @Test
    fun admissionRemainsFifoAfterCapacityChangesAndCancelledWaiters() = runTest {
        val scheduler = HarnessResourceScheduler(
            HarnessResourceBudget(maxModelRequests = 1, maxAgents = 1),
        )
        val held = scheduler.acquire(HarnessResourceKind.MODEL_REQUEST)
        val order = mutableListOf<Int>()
        val cancelled = async {
            scheduler.withResource(HarnessResourceKind.MODEL_REQUEST) { order += 0 }
        }
        runCurrent()
        val first = async {
            scheduler.withResource(HarnessResourceKind.MODEL_REQUEST) { order += 1 }
        }
        runCurrent()
        val second = async {
            scheduler.withResource(HarnessResourceKind.MODEL_REQUEST) { order += 2 }
        }
        runCurrent()
        cancelled.cancelAndJoin()
        scheduler.updateBudget(HarnessResourceBudget(maxModelRequests = 1, maxAgents = 2))
        held.close()
        first.await()
        second.await()
        assertEquals(listOf(1, 2), order)
        assertTrue(scheduler.snapshot().leases.isEmpty())
    }

}
