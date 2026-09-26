package com.labteto.dshmobile.harness.jobs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HarnessJobPersistenceTest {
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
    fun persistentAgentInboxSurvivesRestartAndIsConsumedExactlyOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val first = HarnessJobManager(scope = this, onChanged = { })
        val started = first.startPersistent(
            label = "子代理：research",
            resumeKind = "subagent_readonly",
            resumePayload = "{\"session_id\":\"s\"}",
        ) { _, _ ->
            gate.await()
            "done"
        }
        val id = started.substringAfterLast('：')
        runCurrent()

        assertTrue(first.send(id, "继续检查").contains("已发送"))
        val durable = first.snapshots().single { it.id == id }
        assertEquals(listOf("继续检查"), durable.inbox)

        first.stopAllAndJoin()

        lateinit var restarted: HarnessJobManager
        restarted = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(durable.copy(status = "running")),
        )
        val resumed = restarted.resumePersistent(id) { jobId, _ ->
            restarted.drainMessages(jobId).joinToString("|")
        }
        assertTrue(resumed.contains("已恢复"))
        advanceUntilIdle()

        assertTrue(restarted.output(id).contains("继续检查"))
        assertTrue(restarted.snapshots().single { it.id == id }.inbox.isEmpty())
    }

}
