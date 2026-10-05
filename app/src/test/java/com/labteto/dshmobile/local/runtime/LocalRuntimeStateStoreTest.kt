package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalRuntimeStateStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun initializePublishesOneSharedStateAndRejectsSecondOwner() {
        val store = LocalRuntimeStateStore()
        val initial = LocalHarnessState(sessionId = "session-a")

        val mutable = store.initialize(initial)

        assertEquals("session-a", mutable.value.sessionId)
        assertEquals("session-a", store.state.value.sessionId)
        assertEquals("session-a", store.currentSessionId)

        store.activateSession("session-c")
        assertEquals("session-c", store.currentSessionId)
        // Visible projection changes only when Session lifecycle loads/projects that conversation.
        assertEquals("session-a", store.state.value.sessionId)

        try {
            store.initialize(LocalHarnessState(sessionId = "session-b"))
            fail("第二个运行状态所有者不应重新初始化共享 Store")
        } catch (_: IllegalStateException) {
            assertEquals("session-a", store.state.value.sessionId)
            assertEquals("session-c", store.currentSessionId)
        }
    }
    @Test
    fun foregroundCancellationUsesSharedJobOwnerAndClearsQueuedProjection() {
        val store = LocalRuntimeStateStore()
        store.initialize(
            LocalHarnessState(
                sessionId = "session-a",
                kernel = LocalHarnessState().kernel.copy(
                    running = true,
                    queuedInputCount = 3,
                ),
            ),
        )
        val job = Job()
        store.foregroundJob = job
        val log = LocalSessionEventLog(
            file = File(temporary.root, "session-a.events.jsonl"),
            json = Json { ignoreUnknownKeys = true },
            sessionId = "session-a",
        )
        try {
            assertTrue(store.cancelForegroundRun(log))
            assertTrue(job.isCancelled)
            assertTrue(store.state.value.kernel.running)
            assertEquals(0, store.state.value.kernel.queuedInputCount)
        } finally {
            log.close()
        }
    }

    @Test
    fun failedInboxCancellationWriteStillCancelsJobAndClearsVisibleQueue() {
        val store = LocalRuntimeStateStore()
        store.initialize(LocalHarnessState(
            sessionId = "session-a",
            kernel = LocalKernelState(running = true, queuedInputCount = 1),
        ))
        val job = Job()
        store.foregroundJob = job
        store.foregroundPendingInputs.offer(
            com.labteto.dshmobile.harness.agent.QueuedAgentInput("queued", id = "queued"),
        )
        val blocked = File(temporary.root, "blocked").apply { writeText("not a directory") }
        val log = LocalSessionEventLog(File(blocked, "events.jsonl"), Json)
        try {
            assertTrue(runCatching { store.cancelForegroundRun(log) }.isFailure)
            assertTrue(job.isCancelled)
            assertTrue(store.state.value.kernel.running)
            assertEquals(0, store.state.value.kernel.queuedInputCount)
            assertEquals(0, store.foregroundPendingInputs.size())
        } finally {
            log.close()
        }
    }

    @Test
    fun failedForegroundTeardownLogStillJoinsAndReleasesSharedJobReference() =
        kotlinx.coroutines.runBlocking {
            val store = LocalRuntimeStateStore()
            store.initialize(LocalHarnessState(sessionId = "session-a"))
            val job = Job()
            store.foregroundJob = job
            store.foregroundPendingInputs.offer(
                com.labteto.dshmobile.harness.agent.QueuedAgentInput("queued", id = "queued"),
            )
            val blocked = File(temporary.root, "blocked-join").apply { writeText("not a directory") }
            val log = LocalSessionEventLog(File(blocked, "events.jsonl"), Json)
            try {
                assertTrue(runCatching { store.cancelForegroundRunAndJoin(log) }.isFailure)
                assertTrue(job.isCompleted)
                org.junit.Assert.assertNull(store.foregroundJob)
            } finally {
                log.close()
            }
        }

    @Test
    fun visibleOperationReportsFailureWithoutCrashingOrLeakingToAnotherSession() {
        val store = LocalRuntimeStateStore()
        val ownerState = store.initialize(LocalHarnessState(sessionId = "session-a"))
        store.performVisibleOperation("操作失败") { throw java.io.IOException("disk full") }
        assertTrue(store.state.value.error.orEmpty().contains("disk full"))
        store.performVisibleOperation("旧操作失败") {
            ownerState.value = store.state.value.copy(sessionId = "session-b", error = null)
            throw java.io.IOException("old failure")
        }
        org.junit.Assert.assertNull(store.state.value.error)
        val cancelled = kotlinx.coroutines.CancellationException("cancel")
        assertSame(cancelled, runCatching {
            store.performVisibleOperation("不能吞取消") { throw cancelled }
        }.exceptionOrNull())
    }

}
