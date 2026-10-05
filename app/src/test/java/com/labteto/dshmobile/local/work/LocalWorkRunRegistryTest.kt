package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalJobInfo
import com.labteto.dshmobile.local.LocalPersistentJobStore
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.LocalWorkRunBinding
import com.labteto.dshmobile.harness.jobs.JobSnapshot
import com.labteto.dshmobile.local.runtime.LocalRuntimeJobOwner
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LocalWorkRunRegistryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sharedJobsProjectToLateBindingsWithoutLeakingIntoChatOrOtherSessions() = runTest {
        val runtime = LocalRuntimeStateStore(jobOwner = LocalRuntimeJobOwner(this, null))
        runtime.initialize(LocalHarnessState(sessionId = "chat", usageMode = LocalUsageMode.CHAT))
        val gate = CompletableDeferred<Unit>()
        val firstId = runtime.jobManager.start("first", ownerSessionId = "session-a") { _, report ->
            report("partial")
            gate.await()
            "done"
        }.substringAfterLast('：')
        val secondId = runtime.jobManager.start("second", ownerSessionId = "session-b") { _, _ ->
            awaitCancellation()
        }.substringAfterLast('：')
        val notifications = mutableListOf<List<LocalJobInfo>>()
        val registry = LocalWorkRunRegistry(runtime) { jobs, _ -> notifications += jobs }
        val first = binding("session-a")
        val second = binding("session-b")
        try {
            registry.attach(first)
            registry.attach(second)
            runCurrent()
            assertEquals(listOf(firstId), first.state.value.work.jobs.map { it.id })
            assertEquals(listOf(secondId), second.state.value.work.jobs.map { it.id })
            assertTrue(runtime.state.value.work.jobs.isEmpty())
            assertEquals(2, notifications.last().count { it.status == "running" })
            assertTrue(runtime.jobManager.kill(secondId, "session-a").contains("不存在"))
            gate.complete(Unit)
            runCurrent()
            assertEquals("completed", first.state.value.work.jobs.single().status)
            assertEquals("running", second.state.value.work.jobs.single().status)
            runtime.jobManager.kill(secondId, "session-b")
            runCurrent()
            assertEquals("cancelled", second.state.value.work.jobs.single().status)
            assertEquals(0, notifications.last().count { it.status == "running" })
        } finally {
            runtime.jobManager.stopAllAndJoin()
            first.eventLog.close()
            second.eventLog.close()
        }
    }

    @Test
    fun restartedJobsReplayAsInterruptedToLateBindingsWithoutRunningNotification() = runTest {
        val store = LocalPersistentJobStore(File(temporary.root, "jobs.json"), json)
        store.write(listOf(JobSnapshot(
            id = "job-restored", label = "restore", status = "running",
            resumeKind = "web_fetch", resumePayload = "{}", ownerId = "session-a",
        )))
        val runtime = LocalRuntimeStateStore(jobOwner = LocalRuntimeJobOwner(this, store))
        runtime.initialize(LocalHarnessState(sessionId = "chat", usageMode = LocalUsageMode.CHAT))
        val notifications = mutableListOf<List<LocalJobInfo>>()
        val registry = LocalWorkRunRegistry(runtime) { jobs, _ -> notifications += jobs }
        val restored = binding("session-a")
        try {
            registry.attach(restored)
            assertEquals("interrupted", restored.state.value.work.jobs.single().status)
            assertTrue(runtime.state.value.work.jobs.isEmpty())
            assertTrue(notifications.last().none { it.status == "running" })
            assertEquals("session-a", runtime.jobManager.interruptedSnapshots().single().ownerId)
            assertEquals("interrupted", store.read().single().status)
        } finally {
            restored.eventLog.close()
        }
    }

    @Test
    fun registryOwnsAttachLiveAndDetachLifecycle() {
        val registry = LocalWorkRunRegistry(LocalRuntimeStateStore())
        val first = binding("session-a")
        val second = binding("session-b")
        val firstJob = Job()
        first.job = firstJob
        second.job = Job()

        assertNull(registry.attach(first))
        assertNull(registry.attach(second))
        assertSame(first, registry["session-a"])
        assertSame(first.state.value, registry.state("session-a"))
        assertSame(first, registry.live("session-a"))
        assertTrue(registry.anyLive())

        firstJob.cancel()
        assertNull(registry.live("session-a"))

        val removed = registry.detachAll(setOf("session-a", "session-b"))
        assertEquals(setOf(first, second), removed.toSet())
        assertFalse(registry.anyLive())
        assertNull(registry["session-a"])

        first.eventLog.close()
        second.eventLog.close()
    }

    @Test
    fun attachingSameSessionReplacesPreviousBindingAtomically() {
        val registry = LocalWorkRunRegistry(LocalRuntimeStateStore())
        val first = binding("shared")
        val replacement = binding("shared")

        assertNull(registry.attach(first))
        assertSame(first, registry.attach(replacement))
        assertSame(replacement, registry["shared"])
        assertFalse(registry.detach(first))
        assertSame(replacement, registry["shared"])
        assertTrue(registry.detach(replacement))
        assertNull(registry["shared"])

        first.eventLog.close()
        replacement.eventLog.close()
    }

    private fun binding(sessionId: String): LocalWorkRunBinding =
        LocalWorkRunBinding(
            sessionId = sessionId,
            initialState = LocalHarnessState(usageMode = LocalUsageMode.WORK),
            initialHistory = emptyList(),
            eventLog = LocalSessionEventLog(
                file = File(temporary.root, "$sessionId.events.jsonl"),
                json = json,
                sessionId = sessionId,
            ),
            initialTranscriptProjectionCursor = null,
            maxPendingInputs = 8,
            pruneToolResult = { it },
        )
}
