package com.labteto.dshmobile.harness.jobs

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
