package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.jobs.JobSnapshot
import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalPersistentJobStore
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.runtime.LocalAgentRunHandle
import com.labteto.dshmobile.local.runtime.LocalRuntimeJobOwner
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.send.LocalPreparedSend
import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
        val registry = LocalWorkRunRegistry(
            runtimeStateStore = runtime,
            notifyJobs = { jobs, _ -> notifications += jobs },
        )
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
        val registry = LocalWorkRunRegistry(
            runtimeStateStore = runtime,
            notifyJobs = { jobs, _ -> notifications += jobs },
        )
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
        first.runHandle.job = firstJob
        second.runHandle.job = Job()

        assertNull(registry.attach(first))
        assertNull(registry.attach(second))
        assertSame(first, registry["session-a"])
        assertEquals(first.aggregateSnapshot(), registry.state("session-a"))
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
    fun liveWorkBindingOwnsAdditionalInputQueueAndDurableProjection() {
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(
            LocalHarnessState(
                loading = false,
                sessionId = "session-a",
                usageMode = LocalUsageMode.WORK,
                modelState = LocalModelState(configured = true),
            ),
        )
        var persisted: LocalHarnessSession? = null
        val registry = LocalWorkRunRegistry(
            runtimeStateStore = runtime,
            persistBinding = { binding -> persisted = binding.persistenceSnapshot() },
        )
        val active = binding("session-a")
        val activeJob = Job()
        active.runHandle.job = activeJob
        registry.attach(active)
        try {
            val result = registry.enqueueIntoLiveRun(
                LocalPreparedSend(
                    content = "追加条件",
                    memoryInput = "追加条件",
                    modelMessage = buildJsonObject {
                        put("role", "user")
                        put("content", "追加条件")
                    },
                ),
            )

            assertEquals(LocalSendResult.Queued, result)
            assertEquals(1, active.runHandle.pendingInputs.size())
            assertEquals(1, active.state.value.kernel.queuedInputCount)
            assertEquals("追加条件", active.state.value.messages.last().content)
            assertEquals("session-a", persisted?.id)
            assertEquals(1, persisted?.transcriptWindow?.size)
        } finally {
            activeJob.cancel()
            active.eventLog.close()
        }
    }

    @Test
    fun liveWorkBindingRejectsAdditionalInputDuringSessionTransition() {
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(
            LocalHarnessState(
                loading = false,
                sessionId = "session-a",
                usageMode = LocalUsageMode.WORK,
                modelState = LocalModelState(configured = true),
            ),
        )
        val registry = LocalWorkRunRegistry(runtime)
        val active = binding("session-a")
        val activeJob = Job()
        active.runHandle.job = activeJob
        registry.attach(active)
        assertTrue(runtime.beginSessionTransition())
        try {
            val result = registry.enqueueIntoLiveRun(
                LocalPreparedSend(
                    content = "不能现在排队",
                    memoryInput = "不能现在排队",
                    modelMessage = buildJsonObject {
                        put("role", "user")
                        put("content", "不能现在排队")
                    },
                ),
            )

            assertEquals(LocalSendRejectReason.SESSION_TRANSITION, result?.rejectReason)
            assertEquals(0, active.runHandle.pendingInputs.size())
            assertEquals(0, active.state.value.kernel.queuedInputCount)
        } finally {
            runtime.endSessionTransition()
            activeJob.cancel()
            active.eventLog.close()
        }
    }

    @Test
    fun cancellationTargetsOnlyTheRequestedSessionBindingAndDoesNotPublishIdleEarly() {
        val registry = LocalWorkRunRegistry(LocalRuntimeStateStore())
        val first = binding("session-a")
        val second = binding("session-b")
        val firstJob = Job()
        val secondJob = Job()
        first.runHandle.job = firstJob
        second.runHandle.job = secondJob
        first.state.value = first.state.value.copy(
            kernel = first.state.value.kernel.copy(running = true),
        )
        second.state.value = second.state.value.copy(
            kernel = second.state.value.kernel.copy(running = true),
        )
        registry.attach(first)
        registry.attach(second)

        assertTrue(registry.requestCancel("session-a"))
        assertTrue(firstJob.isCancelled)
        assertTrue(first.state.value.kernel.running)
        assertFalse(secondJob.isCancelled)
        assertTrue(second.state.value.kernel.running)
        assertFalse(registry.requestCancel("missing"))

        first.eventLog.close()
        secondJob.cancel()
        second.eventLog.close()
    }

    @Test
    fun finishTurnDetachesIdleBindingAndMirrorsDurableHistoryToVisibleRuntime() {
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(
            LocalHarnessState(
                loading = false,
                sessionId = "session-a",
                usageMode = LocalUsageMode.WORK,
                modelState = LocalModelState(configured = true),
            ),
        )
        var persisted: LocalHarnessSession? = null
        val registry = LocalWorkRunRegistry(
            runtimeStateStore = runtime,
            persistBinding = { binding -> persisted = binding.persistenceSnapshot() },
        )
        val active = binding("session-a")
        active.runHandle.modelHistory.append(buildJsonObject {
            put("role", "assistant")
            put("content", "已完成")
        })
        active.runHandle.transcriptProjectionCursor = 42L
        val completedJob = Job()
        active.runHandle.job = completedJob
        registry.attach(active)

        try {
            val next = registry.finishTurn(active, completedJob) { _, _ ->
                error("无待处理输入时不得启动下一轮")
            }

            assertNull(next)
            assertNull(registry["session-a"])
            assertNull(active.runHandle.job)
            assertEquals(
                active.runHandle.modelHistory.snapshot(),
                runtime.foregroundRunHandle.modelHistory.snapshot(),
            )
            assertEquals(42L, runtime.foregroundRunHandle.transcriptProjectionCursor)
            assertEquals("session-a", persisted?.id)
            assertNotNull(active.eventLog.latest(ModelHistoryCheckpointCodec.EVENT_TYPE))
        } finally {
            completedJob.cancel()
            active.eventLog.close()
        }
    }

    @Test
    fun finishTurnClaimsQueuedInputAndKeepsBindingOwnedForNextRun() {
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(
            LocalHarnessState(
                loading = false,
                sessionId = "session-a",
                usageMode = LocalUsageMode.WORK,
                modelState = LocalModelState(configured = true),
            ),
        )
        var persistCount = 0
        val registry = LocalWorkRunRegistry(
            runtimeStateStore = runtime,
            persistBinding = { persistCount += 1 },
        )
        val active = binding("session-a")
        val completedJob = Job()
        active.runHandle.job = completedJob
        val queued = QueuedAgentInput(
            content = "继续处理",
            memoryInput = "继续处理",
            modelMessage = buildJsonObject {
                put("role", "user")
                put("content", "继续处理")
            },
            id = "queued-next",
        )
        active.runHandle.pendingInputs.offer(queued) {}
        active.state.value = active.state.value.copy(
            kernel = active.state.value.kernel.copy(queuedInputCount = 1),
        )
        registry.attach(active)
        val nextJob = Job()
        var claimed: QueuedAgentInput? = null

        try {
            val next = registry.finishTurn(active, completedJob) { input, owner ->
                assertSame(active, owner)
                claimed = input
                nextJob
            }

            assertSame(nextJob, next)
            assertSame(nextJob, active.runHandle.job)
            assertSame(active, registry["session-a"])
            assertEquals("queued-next", claimed?.id)
            assertEquals(0, active.runHandle.pendingInputs.size())
            assertEquals(0, active.state.value.kernel.queuedInputCount)
            assertTrue(active.runHandle.modelHistory.snapshot().last().toString().contains("继续处理"))
            assertNotNull(active.eventLog.latest(LOCAL_AGENT_INBOX_EVENT_TYPE))
            assertTrue(persistCount >= 2)
        } finally {
            completedJob.cancel()
            nextJob.cancel()
            active.eventLog.close()
        }
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
            initialState = LocalHarnessState(
                sessionId = sessionId,
                usageMode = LocalUsageMode.WORK,
            ).toLocalWorkRunState(),
            sessionBase = LocalHarnessSession(
                id = sessionId,
                usageMode = LocalUsageMode.WORK,
            ),
            runHandle = LocalAgentRunHandle(
                initialSessionId = sessionId,
                maxPendingInputs = 8,
            ),
            eventLog = LocalSessionEventLog(
                file = File(temporary.root, "$sessionId.events.jsonl"),
                json = json,
                sessionId = sessionId,
            ),
        )
}
