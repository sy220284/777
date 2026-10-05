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

        assertSame(mutable, store.mutableState)
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

}
