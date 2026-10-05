package com.labteto.dshmobile.harness.jobs

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HarnessJobPersistenceTest {
    @Test
    fun delayedRunningPublicationCannotOverwriteConcurrentCancellation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workers = Executors.newFixedThreadPool(2)
        val runningCallback = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val cancelledCallback = CountDownLatch(1)
        val held = AtomicBoolean(false)
        val durable = AtomicReference<List<JobSnapshot>>(emptyList())
        val manager = HarnessJobManager(
            scope = scope,
            idFactory = { "job-race" },
            onChanged = { jobs ->
                if (jobs.any { it.status == "running" } && held.compareAndSet(false, true)) {
                    runningCallback.countDown()
                    check(releaseCallback.await(5, TimeUnit.SECONDS))
                }
                if (jobs.any { it.status == "cancelled" }) cancelledCallback.countDown()
            },
            onSnapshotsChanged = durable::set,
        )
        try {
            val start = workers.submit<String> {
                manager.start("race", ownerId = "session-a") { _, _ -> awaitCancellation() }
            }
            assertTrue(runningCallback.await(5, TimeUnit.SECONDS))
            val cancel = workers.submit<String> { manager.kill("job-race", "session-a") }
            runBlocking {
                withTimeout(5_000) {
                    while (manager.snapshots().single().status != "cancelled") delay(1)
                }
            }
            // The record lock remains available, but a later publication cannot overtake the
            // callback which is still committing the earlier generation.
            assertFalse(cancelledCallback.await(200, TimeUnit.MILLISECONDS))
            releaseCallback.countDown()
            start.get(5, TimeUnit.SECONDS)
            cancel.get(5, TimeUnit.SECONDS)
            runBlocking { manager.stopAllAndJoin() }
            assertTrue(cancelledCallback.await(5, TimeUnit.SECONDS))
            assertTrue(durable.get().single().status == "cancelled")
        } finally {
            releaseCallback.countDown()
            scope.cancel()
            workers.shutdownNow()
        }
    }

    @Test
    fun cancelledJobKeepsItsSlotAndRemainsOwnedUntilCleanupCompletes() = runTest {
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manager = HarnessJobManager(this, {}, maxConcurrentJobs = 1, maxRetainedJobs = 1)
        val first = manager.start("first", ownerId = "session") { _, _ ->
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
            }
        }.substringAfterLast('：')
        runCurrent()
        manager.kill(first)
        runCurrent()
        assertTrue(cleaning.isCompleted)
        assertTrue(manager.availableSlots() == 0)
        assertTrue(manager.start("second") { _, _ -> "done" }.contains("并发已满"))
        assertTrue(manager.snapshots().any { it.id == first })
        val stopping = launch { manager.stopAllAndJoin() }
        runCurrent()
        assertFalse(stopping.isCompleted)
        release.complete(Unit)
        stopping.join()
        assertTrue(manager.availableSlots() == 1)
        manager.start("second") { _, _ -> "done" }
        runCurrent()
        assertTrue(manager.snapshots().none { it.id == first })
    }

    @Test
    fun removingAnOwnerWaitsForAlreadyCancelledJobsAndRejectsNewOwnedWork() = runTest {
        val release = CompletableDeferred<Unit>()
        val manager = HarnessJobManager(this, {}, maxConcurrentJobs = 2)
        val first = manager.start("first", ownerId = "session") { _, _ ->
            try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
        }.substringAfterLast('：')
        runCurrent()
        manager.kill(first)
        val removal = launch { manager.removeOwnedAndJoin(setOf("session")) }
        runCurrent()
        assertFalse(removal.isCompleted)
        assertTrue(manager.start("late", ownerId = "session") { _, _ -> "done" }.contains("正在移除"))
        release.complete(Unit)
        removal.join()
        assertTrue(manager.snapshots().isEmpty())
        assertTrue(manager.availableSlots() == 2)
    }

    @Test
    fun restartedShellJobReportsInterruptionWithoutSuggestingRecovery() = runTest {
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(JobSnapshot("job-device", "后台命令", "running")),
        )
        assertTrue(manager.output("job-device").contains("后台命令未完成"))
        assertTrue(manager.interruptedSnapshots().isEmpty())
    }

    @Test
    fun persistentStartDoesNotLaunchWhenDurableSnapshotWriteFails() = runTest {
        var ran = false
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            onSnapshotsChanged = { error("disk full") },
        )

        val failure = runCatching {
            manager.startPersistent(
                label = "durable",
                resumeKind = "web_fetch",
                resumePayload = "{\"url\":\"https://example.com\"}",
            ) { _, _ ->
                ran = true
                "done"
            }
        }.exceptionOrNull()

        runCurrent()

        assertTrue(failure is IllegalStateException)
        assertFalse(ran)
        assertTrue(manager.list() == "没有后台任务")
    }


    @Test
    fun timedEphemeralStartDoesNotLaunchWithoutDurableDeadlineEvidence() = runTest {
        var ran = false
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            onSnapshotsChanged = { error("disk full") },
        )

        val failure = runCatching {
            manager.start(
                label = "后台命令",
                expectedDurationMillis = 900_000L,
            ) { _, _ ->
                ran = true
                "done"
            }
        }.exceptionOrNull()

        runCurrent()

        assertTrue(failure is IllegalStateException)
        assertFalse(ran)
        assertTrue(manager.list() == "没有后台任务")
    }

    @Test
    fun interruptedPersistentJobsCanResumeInBatchesWithoutStrandingOverflow() = runTest {
        val gates = (1..8).associateWith { CompletableDeferred<Unit>() }
        val snapshots = (1..8).map { index ->
            JobSnapshot(
                id = "job-$index",
                label = "persistent-$index",
                status = "running",
                resumeKind = "web_fetch",
                resumePayload = "{\"url\":\"https://example.com/$index\"}",
            )
        }
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            maxConcurrentJobs = 4,
            initialSnapshots = snapshots,
        )

        fun fillAvailableSlots() {
            manager.interruptedSnapshots()
                .take(manager.availableSlots())
                .forEach { snapshot ->
                    val index = snapshot.id.substringAfterLast('-').toInt()
                    manager.resumePersistent(snapshot.id) { _, _ ->
                        gates.getValue(index).await()
                        "done-$index"
                    }
                }
        }

        fillAvailableSlots()
        runCurrent()

        assertTrue(manager.availableSlots() == 0)
        assertTrue(manager.interruptedSnapshots().size == 4)

        (1..4).forEach { gates.getValue(it).complete(Unit) }
        advanceUntilIdle()

        assertTrue(manager.availableSlots() == 4)
        fillAvailableSlots()
        runCurrent()
        assertTrue(manager.interruptedSnapshots().isEmpty())

        (5..8).forEach { gates.getValue(it).complete(Unit) }
        advanceUntilIdle()

        assertTrue((1..8).all { index ->
            manager.output("job-$index").contains("[completed]")
        })
    }

    @Test
    fun sessionTransitionCanStopEphemeralJobsWithoutCancellingPersistentJobs() = runTest {
        val ephemeralGate = CompletableDeferred<Unit>()
        val persistentGate = CompletableDeferred<Unit>()
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
        )

        val ephemeral = manager.start("ephemeral") { _, _ ->
            ephemeralGate.await()
            "ephemeral done"
        }
        val persistent = manager.startPersistent(
            label = "persistent",
            resumeKind = "web_fetch",
            resumePayload = "{\"url\":\"https://example.com\"}",
        ) { _, _ ->
            persistentGate.await()
            "persistent done"
        }
        val ephemeralId = ephemeral.substringAfterLast('：')
        val persistentId = persistent.substringAfterLast('：')

        runCurrent()
        manager.stopNonPersistentAndJoin()

        assertTrue(manager.output(ephemeralId).contains("[cancelled]"))
        assertTrue(manager.output(persistentId).contains("[running]"))
        assertTrue(manager.availableSlots() >= 1)

        persistentGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(manager.output(persistentId).contains("[completed]"))
    }

    @Test
    fun removingOneSessionsJobsDoesNotCancelAnotherSessionsWork() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val manager = HarnessJobManager(scope = this, onChanged = { })

        val first = manager.start(label = "first", ownerId = "session-a") { _, _ ->
            firstGate.await()
            "first done"
        }.substringAfterLast('：')
        val second = manager.start(label = "second", ownerId = "session-b") { _, _ ->
            secondGate.await()
            "second done"
        }.substringAfterLast('：')
        runCurrent()

        manager.removeOwnedAndJoin(setOf("session-a"))

        assertTrue(manager.output(first).contains("不存在"))
        assertTrue(manager.output(second).contains("[running]"))

        secondGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(manager.output(second).contains("[completed]"))
    }

    @Test
    fun persistentJobSnapshotKeepsOwningSessionAcrossRestart() = runTest {
        var snapshots = emptyList<JobSnapshot>()
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            onSnapshotsChanged = { snapshots = it },
        )
        val gate = CompletableDeferred<Unit>()

        manager.startPersistent(
            label = "durable",
            resumeKind = "web_fetch",
            resumePayload = "{\"session_id\":\"session-a\"}",
            ownerId = "session-a",
        ) { _, _ ->
            gate.await()
            "done"
        }
        runCurrent()

        assertTrue(snapshots.single().ownerId == "session-a")

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun persistentTaskKilledDuringDurablePreflightNeverStartsLater() = runTest {
        var ran = false
        var killedDuringPreflight = false
        lateinit var manager: HarnessJobManager
        manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            idFactory = { "job-preflight-race" },
            onSnapshotsChanged = { snapshots ->
                if (!killedDuringPreflight &&
                    snapshots.any { it.id == "job-preflight-race" && it.status == "running" }
                ) {
                    killedDuringPreflight = true
                    manager.kill("job-preflight-race")
                }
            },
        )

        manager.startPersistent(
            label = "durable",
            resumeKind = "web_fetch",
            resumePayload = "{\"url\":\"https://example.com\"}",
        ) { _, _ ->
            ran = true
            "must not run"
        }
        runCurrent()

        assertTrue(killedDuringPreflight)
        assertFalse(ran)
        assertTrue(manager.output("job-preflight-race").contains("[cancelled]"))
    }


    @Test
    fun duplicateIdFactoryFallsBackInsteadOfLoopingForever() = runTest {
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            idFactory = { "job-duplicate" },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-duplicate",
                    label = "旧任务",
                    status = "completed",
                ),
            ),
        )

        val started = manager.start("新任务") { _, _ -> "done" }
        runCurrent()

        val id = started.substringAfterLast('：')
        assertTrue(id != "job-duplicate")
        assertTrue(id.startsWith("job-"))
        assertTrue(manager.output(id).contains("[completed]"))
        assertTrue(manager.output("job-duplicate").contains("[completed]"))
    }

}
