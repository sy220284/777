package com.labteto.dshmobile.harness.jobs

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.harness.agent.QueuedAgentInput

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.test.StandardTestDispatcher
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
    fun deletionFailureRetainsOwnedJobReceiptsUntilDurableCommit() = runTest {
        val manager = HarnessJobManager(backgroundScope, {})
        val never = CompletableDeferred<Unit>()
        manager.start("unfinished", ownerId = "session-a") { _, progress ->
            progress("工作已完成一半：重要证据")
            never.await()
            "should-not-complete"
        }
        manager.start("finished", ownerId = "session-a") { _, _ -> "completed-result" }
        manager.start("other", ownerId = "session-b") { _, _ -> "other-result" }
        runCurrent()
        manager.stopOwnedAndJoin(setOf("session-a"))

        val retained = manager.snapshots().filter { it.ownerId == "session-a" }
        assertEquals(2, retained.size)
        assertEquals("cancelled", retained.single { it.label == "unfinished" }.status)
        assertTrue(retained.single { it.label == "unfinished" }.output.contains("工作已完成一半：重要证据"))
        assertTrue(retained.single { it.label == "unfinished" }.output.contains("任务已取消"))
        assertEquals("completed", retained.single { it.label == "finished" }.status)
        assertTrue(retained.any { it.output.contains("completed-result") })
        assertTrue(manager.snapshots().any { it.ownerId == "session-b" })

        manager.removeOwnedAndJoin(setOf("session-a"))
        assertTrue(manager.snapshots().none { it.ownerId == "session-a" })
        assertTrue(manager.snapshots().any { it.ownerId == "session-b" })
    }

    @Test
    fun recoveryProtocolMismatchPreservesUnknownOutcomeAcrossProcessRestart() = runTest {
        val durable = mutableListOf<List<JobSnapshot>>()
        val original = JobSnapshot(
            id = "side-effect-unknown",
            label = "有副作用的外部请求",
            status = "running",
            output = "请求已经发送到外部系统",
            resumeKind = "future_external_tool",
            resumePayload = "{}",
            ownerId = "owner-a",
        )
        val manager = HarnessJobManager(
            scope = backgroundScope,
            onChanged = {},
            initialSnapshots = listOf(original),
            onSnapshotsChanged = { durable += it },
        )
        assertEquals("interrupted", manager.snapshots().single().status)
        val outcome = manager.markRecoveryUnverifiable(
            "side-effect-unknown", "不支持的恢复类型 future_external_tool",
        )
        assertTrue(outcome.contains("人工核实"))
        assertEquals("interrupted", manager.snapshots().single().status)
        assertTrue(manager.snapshots().single().output.contains("结果未知"))
        assertTrue(manager.snapshots().single().output.contains("请求已经发送"))
        assertEquals("future_external_tool", manager.snapshots().single().resumeKind)
        assertTrue(durable.last().single().status != "failed")
        val recovered = HarnessJobManager(
            scope = backgroundScope,
            onChanged = {},
            initialSnapshots = durable.last(),
        )
        assertEquals("interrupted", recovered.snapshots().single().status)
        assertTrue(recovered.snapshots().single().output.contains("人工核实"))
    }

    @Test
    fun completedTaskIgnoresProgressFromItsCapturedCallback() = runTest {
        var late: ((String) -> Unit)? = null
        val manager = HarnessJobManager(backgroundScope, {})
        val id = manager.start("finished", ownerId = "owner") { _, progress ->
            late = progress
            "durable completed output"
        }.substringAfterLast('：')
        runCurrent()
        val before = manager.snapshots().single()
        assertEquals("completed", before.status)
        late!!.invoke("LATE progress must not overwrite result")
        assertEquals(before, manager.snapshots().single())
        assertTrue(manager.output(id).contains("durable completed output"))
    }

    @Test
    fun cancelledTaskIgnoresProgressFromItsCapturedCallback() = runTest {
        var late: ((String) -> Unit)? = null
        val never = CompletableDeferred<Unit>()
        val manager = HarnessJobManager(backgroundScope, {})
        val id = manager.start("running", ownerId = "owner") { _, progress ->
            late = progress
            never.await()
            "unexpected"
        }.substringAfterLast('：')
        runCurrent()
        assertEquals("running", manager.snapshots().single().status)
        manager.kill(id, "owner")
        runCurrent()
        val before = manager.snapshots().single()
        assertEquals("cancelled", before.status)
        late!!.invoke("LATE output from a cancelled executor")
        assertEquals(before, manager.snapshots().single())
    }

    @Test
    fun cancelledJobKeepsCancellationWhenBlockingWorkThrowsLate() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manager = HarnessJobManager(scope, {})
        try {
            val id = manager.start("blocking", ownerId = "session-a") { _, _ ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                error("late failure")
            }.substringAfterLast('：')
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            manager.kill(id, "session-a")
            release.countDown()
            runBlocking { manager.stopAllAndJoin() }
            assertTrue(manager.snapshots().single().status == "cancelled")
            assertFalse(manager.output(id).contains("late failure"))
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun cancelledJobCannotCommitSuccessWhenBlockingWorkReturnsLate() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manager = HarnessJobManager(scope, {})
        try {
            val id = manager.start("blocking", ownerId = "session-a") { _, _ ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "late success"
            }.substringAfterLast('：')
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            manager.kill(id, "session-a")
            release.countDown()
            runBlocking { manager.stopAllAndJoin() }
            assertTrue(manager.snapshots().single().status == "cancelled")
            assertFalse(manager.output(id).contains("late success"))
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

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
    fun persistentResumeCannotTransferAnOwnedJobAcrossSessions() = runTest {
        var launched = false
        val manager = HarnessJobManager(
            scope = backgroundScope,
            onChanged = {},
            initialSnapshots = listOf(JobSnapshot(
                id = "job-owned", label = "子代理：持久工作", status = "interrupted",
                resumeKind = "subagent_readonly", resumePayload = "{}", ownerId = "session-a",
            )),
        )
        val original = manager.snapshots().single()
        val denied = manager.resumePersistent("job-owned", ownerId = "session-b") { _, _ ->
            launched = true
            "unexpected"
        }
        assertTrue(denied.contains("不属于当前会话"))
        assertEquals(original, manager.snapshots().single())
        assertFalse(launched)
        assertTrue(manager.availableSlots() > 0)

        val allowed = manager.resumePersistent("job-owned", ownerId = "session-a") { _, _ ->
            launched = true
            "legitimate"
        }
        assertTrue(allowed.contains("已恢复"))
        runCurrent()
        assertTrue(launched)
        assertEquals("session-a", manager.snapshots().single().ownerId)
    }

    @Test
    fun previouslyUnownedLegacyJobMayAcquireItsFirstSessionOwnerOnResume() = runTest {
        val manager = HarnessJobManager(
            scope = this,
            onChanged = {},
            initialSnapshots = listOf(JobSnapshot(
                id = "legacy", label = "后台抓取", status = "interrupted",
                resumeKind = "web_fetch", resumePayload = "{}", ownerId = null,
            )),
        )
        assertTrue(manager.resumePersistent("legacy", "session-a") { _, _ -> "completed" }
            .contains("已恢复"))
        runCurrent()
        assertEquals("session-a", manager.snapshots().single().ownerId)
    }

    @Test
    fun subagentRequestSliceBoundaryPersistsAndCanResumeAfterExplicitRootAdmission() = runTest {
        val durable = java.util.concurrent.atomic.AtomicReference<List<JobSnapshot>>(emptyList())
        val manager = HarnessJobManager(
            scope = this, onChanged = { }, onSnapshotsChanged = durable::set,
            maxConcurrentJobs = 2,
        )
        val started = manager.startPersistent(
            label = "子代理：预算边界",
            resumeKind = "subagent_readonly",
            resumePayload = "{}",
            ownerId = "session-A",
            continuable = true,
        ) { _, _ ->
            throw JobExecutionSliceBoundaryException(IllegalStateException("WORK_BUDGET_EXHAUSTED"))
        }
        val id = started.substringAfterLast('：')
        runCurrent()
        assertEquals("waiting_slice", manager.snapshots().single().status)
        assertEquals("waiting_slice", durable.get().single().status)
        assertEquals(1, manager.resumableSnapshots().size)
        assertEquals(2, manager.availableSlots())

        val restored = HarnessJobManager(
            scope = this, onChanged = { }, initialSnapshots = durable.get(),
        )
        assertEquals("waiting_slice", restored.snapshots().single().status)
        assertTrue(restored.infos().single().canMessage)
        assertTrue(restored.resumableSnapshots().any { it.id == id })
        val queued = restored.sendInput(
            id, QueuedAgentInput("从当前检查点继续", id = "msg-continue"), ownerId = "session-A",
        )
        assertTrue(queued.accepted)
        assertTrue(queued.requiresResume)
        assertEquals("msg-continue", restored.snapshots().single().inbox.single().id)
        val stopped = HarnessJobManager(scope = this, onChanged = { }, initialSnapshots = durable.get())
        assertTrue(stopped.kill(id, ownerId = "session-A").contains("已"))
        assertEquals("cancelled", stopped.snapshots().single().status)
        assertTrue(stopped.resumableSnapshots().isEmpty())
        val resumed = restored.resumePersistent(id, ownerId = "session-A") { _, _ -> "已完成" }
        assertTrue(resumed.contains("已恢复"))
        runCurrent()
        assertEquals("dormant", restored.snapshots().single().status)
        assertEquals("已完成", restored.snapshots().single().output)
        assertEquals("$id [dormant] 预算边界\n已完成", restored.output(id))
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
    fun persistentStartRejectsOversizedResumePayloadWithoutTruncation() = runTest {
        var ran = false
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
        )

        val failure = runCatching {
            manager.startPersistent(
                label = "oversized",
                resumeKind = "subagent_readonly",
                resumePayload = "x".repeat(64_001),
            ) { _, _ ->
                ran = true
                "done"
            }
        }.exceptionOrNull()

        runCurrent()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("恢复元数据过大"))
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
    fun interruptedPersistentAgentKeepsDurableInboxAndAcceptsColdResumeMessage() = runTest {
        var durable = emptyList<JobSnapshot>()
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "running",
                    resumeKind = "subagent_readonly",
                    resumePayload = """{"session_id":"session-a"}""",
                    ownerId = "session-a",
                    inbox = listOf(QueuedAgentInput(id = "msg-old", content = "已有消息")),
                ),
            ),
            onSnapshotsChanged = { durable = it },
        )

        assertTrue(manager.output("job-agent", "session-a").contains("[interrupted]"))
        val result = manager.send("job-agent", "恢复后继续核查", "session-a")

        assertTrue(result.contains("持久排队"))
        assertTrue(manager.peekMessages("job-agent").map { it.content } == listOf("已有消息", "恢复后继续核查"))
        assertTrue(durable.single().inbox.size == 2)
        assertTrue(durable.single().inbox.last().content == "恢复后继续核查")
    }

    @Test
    fun persistentAgentInboxRejectsOverflowWithoutDroppingOlderMessages() = runTest {
        val existing = (1..AgentInputQueue.DEFAULT_CAPACITY).map { index ->
            QueuedAgentInput(
                id = "msg-$index",
                content = "消息$index",
            )
        }
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "running",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    continuable = true,
                    ownerId = "session-a",
                    inbox = existing,
                ),
            ),
        )

        val result = manager.send("job-agent", "不能挤掉旧消息", "session-a")

        assertTrue(result.contains("消息队列已满"))
        assertTrue(manager.peekMessages("job-agent") == existing)
    }

    @Test
    fun persistentAgentMessageRollsBackWhenDurableWriteFails() = runTest {
        var writes = 0
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "running",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    inbox = listOf(QueuedAgentInput(id = "msg-old", content = "已有消息")),
                ),
            ),
            onSnapshotsChanged = {
                writes += 1
                if (writes >= 2) error("disk full")
            },
        )

        val failure = runCatching {
            manager.send("job-agent", "不能伪装成功", "session-a")
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(manager.peekMessages("job-agent") == listOf(QueuedAgentInput(id = "msg-old", content = "已有消息")))
    }

    @Test
    fun uncommittedPersistentInboxIsInvisibleUntilDurableAdmissionFinishes() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workers = Executors.newFixedThreadPool(2)
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val observerStarted = CountDownLatch(1)
        val observerDone = CountDownLatch(1)
        val writes = AtomicInteger(0)
        val observed = AtomicReference<List<QueuedAgentInput>>(emptyList())
        val manager = HarnessJobManager(
            scope = scope,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "completed",
                    output = "上一轮完成",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    continuable = true,
                ),
            ),
            onSnapshotsChanged = {
                if (writes.incrementAndGet() == 2) {
                    writeEntered.countDown()
                    check(releaseWrite.await(5, TimeUnit.SECONDS))
                    error("disk full")
                }
            },
        )
        try {
            val sender = workers.submit<Throwable?> {
                runCatching {
                    manager.send("job-agent", "尚未落盘的消息", "session-a")
                }.exceptionOrNull()
            }
            assertTrue(writeEntered.await(5, TimeUnit.SECONDS))

            workers.submit {
                observerStarted.countDown()
                observed.set(manager.peekMessages("job-agent"))
                observerDone.countDown()
            }
            assertTrue(observerStarted.await(5, TimeUnit.SECONDS))
            assertFalse(observerDone.await(200, TimeUnit.MILLISECONDS))

            releaseWrite.countDown()
            assertTrue(sender.get(5, TimeUnit.SECONDS) is IllegalStateException)
            assertTrue(observerDone.await(5, TimeUnit.SECONDS))
            assertTrue(observed.get().isEmpty())
            assertTrue(manager.peekMessages("job-agent").isEmpty())
        } finally {
            releaseWrite.countDown()
            scope.cancel()
            workers.shutdownNow()
        }
    }

    @Test
    fun persistentAgentInboxAcknowledgementIsDurableAndScoped() = runTest {
        var durable = emptyList<JobSnapshot>()
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "running",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    inbox = listOf(
                        QueuedAgentInput(id = "msg-1", content = "第一条"),
                        QueuedAgentInput(id = "msg-2", content = "第二条"),
                    ),
                ),
            ),
            onSnapshotsChanged = { durable = it },
        )

        manager.acknowledgeMessages("job-agent", setOf("msg-1"), "session-a")

        assertTrue(manager.peekMessages("job-agent").map { it.id } == listOf("msg-2"))
        assertTrue(durable.single().inbox.map { it.id } == listOf("msg-2"))
        val failure = runCatching {
            manager.acknowledgeMessages("job-agent", setOf("msg-2"), "session-b")
        }.exceptionOrNull()
        assertTrue(failure != null)
        assertTrue(manager.peekMessages("job-agent").single().id == "msg-2")
    }

    @Test
    fun interruptedAgentAtomicallySettlesWhenEveryInboxMessageWasClaimed() = runTest {
        var durable = emptyList<JobSnapshot>()
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "running",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    inbox = listOf(QueuedAgentInput(id = "msg-claimed", content = "已进入检查点")),
                ),
            ),
            onSnapshotsChanged = { durable = it },
        )

        val settled = manager.settleResumableAgentIfInboxClaimed(
            id = "job-agent",
            output = "最终结论",
            claimedMessageIds = setOf("msg-claimed"),
            ownerId = "session-a",
        )

        assertTrue(settled)
        assertTrue(manager.output("job-agent", "session-a").contains("[dormant]"))
        assertTrue(manager.peekMessages("job-agent").isEmpty())
        assertTrue(durable.single().status == "dormant")
        assertTrue(durable.single().inbox.isEmpty())
    }

    @Test
    fun interruptedAgentRefusesAtomicSettlementWhenNewInboxMessageExists() = runTest {
        var durable = emptyList<JobSnapshot>()
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "running",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    inbox = listOf(
                        QueuedAgentInput(id = "msg-claimed", content = "已进入检查点"),
                        QueuedAgentInput(id = "msg-new", content = "继续下一项"),
                    ),
                ),
            ),
            onSnapshotsChanged = { durable = it },
        )

        val settled = manager.settleResumableAgentIfInboxClaimed(
            id = "job-agent",
            output = "旧终态",
            claimedMessageIds = setOf("msg-claimed"),
            ownerId = "session-a",
        )

        assertFalse(settled)
        assertTrue(manager.output("job-agent", "session-a").contains("[interrupted]"))
        assertTrue(manager.peekMessages("job-agent").map { it.id } == listOf("msg-claimed", "msg-new"))
        assertTrue(durable.single().status == "interrupted")
    }

    @Test
    fun interruptedPersistentJobCanSettleFromDurableTerminalCheckpoint() = runTest {
        var durable = emptyList<JobSnapshot>()
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "running",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                ),
            ),
            onSnapshotsChanged = { durable = it },
        )

        val result = manager.completeInterrupted(
            id = "job-agent",
            output = "最终结论",
            ownerId = "session-a",
        )

        assertTrue(result.contains("持久检查点结算"))
        assertTrue(manager.output("job-agent", "session-a").contains("[dormant]"))
        assertTrue(manager.output("job-agent", "session-a").contains("最终结论"))
        assertTrue(durable.single().status == "dormant")
    }

    @Test
    fun completedContinuableAgentAcceptsNewMessageAndBecomesResumable() = runTest {
        var durable = emptyList<JobSnapshot>()
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "completed",
                    output = "第一轮完成",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    continuable = true,
                ),
            ),
            onSnapshotsChanged = { durable = it },
        )

        val result = manager.send("job-agent", "继续检查下一项", "session-a")

        assertTrue(result.contains("持久排队"))
        assertTrue(manager.output("job-agent", "session-a").contains("[dormant]"))
        assertTrue(manager.peekMessages("job-agent").single().content == "继续检查下一项")
        assertTrue(manager.interruptedSnapshots().isEmpty())
        assertTrue(manager.resumableSnapshots().single().id == "job-agent")
        assertTrue(durable.single().status == "dormant")
        assertTrue(durable.single().continuable)
    }

    @Test
    fun completedContinuableMessageRollsBackWhenDurableWriteFails() = runTest {
        var writes = 0
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "completed",
                    output = "第一轮完成",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    continuable = true,
                ),
            ),
            onSnapshotsChanged = {
                writes += 1
                if (writes >= 2) error("disk full")
            },
        )

        val failure = runCatching {
            manager.send("job-agent", "继续检查", "session-a")
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(manager.output("job-agent", "session-a").contains("[dormant]"))
        assertTrue(manager.output("job-agent", "session-a").contains("第一轮完成"))
        assertTrue(manager.peekMessages("job-agent").isEmpty())
        assertTrue(manager.resumableSnapshots().isEmpty())
    }

    @Test
    fun completedNonContinuableAgentRejectsNewMessage() = runTest {
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：一次性",
                    status = "completed",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    continuable = false,
                ),
            ),
        )

        val result = manager.send("job-agent", "继续", "session-a")

        assertTrue(result.contains("不可接收消息"))
        assertTrue(manager.peekMessages("job-agent").isEmpty())
        assertTrue(manager.resumableSnapshots().isEmpty())
    }

    @Test
    fun legacyRunningReadonlySubagentUpgradesToContinuableAfterRestart() = runTest {
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-legacy-agent",
                    label = "子代理：旧版审计",
                    status = "running",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                ),
            ),
        )

        assertTrue(manager.interruptedSnapshots().single().continuable)
        manager.completeInterrupted(
            id = "job-legacy-agent",
            output = "旧任务恢复完成",
            ownerId = "session-a",
        )

        val result = manager.send("job-legacy-agent", "继续下一项", "session-a")

        assertTrue(result.contains("持久排队"))
        assertTrue(manager.resumableSnapshots().single().id == "job-legacy-agent")
    }

    @Test
    fun completedContinuableAgentLaunchesSecondActivationAfterNewMessage() = runTest {
        var secondActivationRan = false
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            idFactory = { "job-agent" },
        )

        manager.startPersistent(
            label = "子代理：审计",
            resumeKind = "subagent_readonly",
            resumePayload = "{}",
            ownerId = "session-a",
            continuable = true,
        ) { _, _ ->
            "第一轮完成"
        }
        advanceUntilIdle()

        assertTrue(manager.output("job-agent", "session-a").contains("[dormant]"))
        assertTrue(manager.resumableSnapshots().isEmpty())
        assertTrue(manager.send("job-agent", "继续下一项", "session-a").contains("持久排队"))
        assertTrue(manager.resumableSnapshots().single().id == "job-agent")

        val resumed = manager.resumePersistent("job-agent", "session-a") { _, _ ->
            secondActivationRan = true
            "第二轮完成"
        }
        assertTrue(resumed.contains("已恢复"))
        advanceUntilIdle()

        assertTrue(secondActivationRan)
        assertTrue(manager.output("job-agent", "session-a").contains("[dormant]"))
        assertTrue(manager.output("job-agent", "session-a").contains("第二轮完成"))
    }

    @Test
    fun interruptContinuableAgentStopsOnlyCurrentActivationAndKeepsDurableInbox() = runTest {
        var durable = emptyList<JobSnapshot>()
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            idFactory = { "job-agent" },
            onSnapshotsChanged = { durable = it },
        )

        manager.startPersistent(
            label = "子代理：team-worker",
            resumeKind = "subagent_readonly",
            resumePayload = "{}",
            ownerId = "session-a",
            continuable = true,
        ) { _, _ ->
            awaitCancellation()
        }
        runCurrent()

        val queued = manager.sendInput(
            id = "job-agent",
            input = QueuedAgentInput(
                id = "team-msg-1",
                content = "继续核查",
                memoryInput = "继续核查",
            ),
            ownerId = "session-a",
        )
        assertTrue(queued.accepted)
        assertFalse(queued.requiresResume)

        val interrupted = manager.interruptContinuableAgent("job-agent", "session-a")
        runCurrent()

        assertTrue(interrupted.contains("当前执行轮次"))
        assertTrue(manager.output("job-agent", "session-a").contains("[interrupted]"))
        assertEquals(listOf("team-msg-1"), manager.peekMessages("job-agent").map { it.id })
        assertEquals("interrupted", durable.single().status)
        assertEquals(listOf("team-msg-1"), durable.single().inbox.map { it.id })

        val next = manager.sendInput(
            id = "job-agent",
            input = QueuedAgentInput(
                id = "team-msg-2",
                content = "再补一项",
                memoryInput = "再补一项",
            ),
            ownerId = "session-a",
        )
        assertTrue(next.accepted)
        assertTrue(next.requiresResume)
        assertEquals(
            listOf("team-msg-1", "team-msg-2"),
            manager.peekMessages("job-agent").map { it.id },
        )
    }

    @Test
    fun interruptedPersistentAgentCanBeCancelledWithoutLaterColdResume() = runTest {
        var durable = emptyList<JobSnapshot>()
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "running",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    inbox = listOf(QueuedAgentInput(id = "msg-1", content = "待处理消息")),
                ),
            ),
            onSnapshotsChanged = { durable = it },
        )

        val result = manager.kill("job-agent", "session-a")

        assertTrue(result.contains("已请求停止"))
        assertTrue(manager.output("job-agent", "session-a").contains("[cancelled]"))
        assertTrue(manager.interruptedSnapshots().none { it.id == "job-agent" })
        assertTrue(durable.single().status == "cancelled")
    }

    @Test
    fun resumableFailureRollsBackWhenDurableWriteFails() = runTest {
        var writes = 0
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "running",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    continuable = true,
                ),
            ),
            onSnapshotsChanged = {
                writes += 1
                if (writes >= 2) error("disk full")
            },
        )

        val failure = runCatching {
            manager.failResumable("job-agent", "恢复路由失效")
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(manager.output("job-agent", "session-a").contains("[interrupted]"))
        assertTrue(manager.resumableSnapshots().single().id == "job-agent")
    }

    @Test
    fun completedContinuableAgentCanBePermanentlyCancelledWhileDormant() = runTest {
        var durable = emptyList<JobSnapshot>()
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "completed",
                    output = "已完成第一轮",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    continuable = true,
                    inbox = listOf(QueuedAgentInput(id = "msg-next", content = "继续")),
                ),
            ),
            onSnapshotsChanged = { durable = it },
        )

        val result = manager.kill("job-agent", "session-a")

        assertTrue(result.contains("已请求停止"))
        assertTrue(manager.output("job-agent", "session-a").contains("[cancelled]"))
        assertTrue(manager.peekMessages("job-agent").isEmpty())
        assertTrue(manager.resumableSnapshots().isEmpty())
        assertTrue(durable.single().status == "cancelled")
        assertTrue(durable.single().inbox.isEmpty())
    }

    @Test
    fun terminalSettlementAtomicallyClearsOnlyClaimedInboxMessages() = runTest {
        var durable = emptyList<JobSnapshot>()
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "running",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    continuable = true,
                    inbox = listOf(QueuedAgentInput(id = "msg-old", content = "已进入检查点")),
                ),
            ),
            onSnapshotsChanged = { durable = it },
        )

        val settled = manager.settleResumableAgentIfInboxClaimed(
            id = "job-agent",
            output = "已完成",
            claimedMessageIds = setOf("msg-old"),
            ownerId = "session-a",
        )

        assertTrue(settled)
        assertTrue(manager.output("job-agent", "session-a").contains("[dormant]"))
        assertTrue(manager.peekMessages("job-agent").isEmpty())
        assertTrue(durable.single().status == "dormant")
        assertTrue(durable.single().inbox.isEmpty())
    }

    @Test
    fun terminalSettlementRefusesToConsumeGenuinelyNewInboxMessage() = runTest {
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "completed",
                    output = "上一轮完成",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    continuable = true,
                    inbox = listOf(
                        QueuedAgentInput(id = "msg-old", content = "已进入检查点"),
                        QueuedAgentInput(id = "msg-new", content = "继续处理"),
                    ),
                ),
            ),
        )

        val settled = manager.settleResumableAgentIfInboxClaimed(
            id = "job-agent",
            output = "上一轮完成",
            claimedMessageIds = setOf("msg-old"),
            ownerId = "session-a",
        )

        assertTrue(!settled)
        assertTrue(manager.output("job-agent", "session-a").contains("[dormant]"))
        assertTrue(
            manager.peekMessages("job-agent").map { it.id } ==
                listOf("msg-old", "msg-new")
        )
    }

    @Test
    fun retentionPruningNeverSilentlyDropsResumableOrContinuableAgents() = runTest {
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            maxConcurrentJobs = 1,
            maxRetainedJobs = 2,
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-interrupted",
                    label = "子代理：中断任务",
                    status = "interrupted",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    continuable = true,
                ),
                JobSnapshot(
                    id = "job-completed",
                    label = "子代理：已完成任务",
                    status = "completed",
                    output = "已完成",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    continuable = true,
                ),
            ),
        )

        val started = manager.start("新任务") { _, _ -> "不应启动" }

        assertTrue(started.contains("保留上限已满"))
        assertTrue(manager.output("job-interrupted", "session-a").contains("[interrupted]"))
        assertTrue(manager.output("job-completed", "session-a").contains("[dormant]"))
        assertTrue(manager.snapshots().map { it.id }.toSet() == setOf("job-interrupted", "job-completed"))
    }

    @Test
    fun retentionPruningStillRemovesOrdinaryFinishedHistory() = runTest {
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            maxConcurrentJobs = 1,
            maxRetainedJobs = 2,
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-old",
                    label = "旧任务",
                    status = "completed",
                ),
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：保留",
                    status = "completed",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    continuable = true,
                ),
            ),
        )

        val started = manager.start("新任务") { _, _ -> "done" }
        runCurrent()

        assertTrue(started.contains("后台任务已启动"))
        assertTrue(manager.output("job-old").contains("不存在"))
        assertTrue(manager.output("job-agent").contains("[dormant]"))
    }

    @Test
    fun continuationCheckpointPersistenceFailureLeavesAgentInterruptedAndResumable() = runTest {
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            idFactory = { "job-agent" },
        )

        manager.startPersistent(
            label = "子代理：审计",
            resumeKind = "subagent_readonly",
            resumePayload = "{}",
            ownerId = "session-a",
            continuable = true,
        ) { _, _ ->
            throw JobContinuationPersistenceException(
                "checkpoint write failed",
                IllegalStateException("disk full"),
            )
        }
        advanceUntilIdle()

        assertTrue(manager.output("job-agent", "session-a").contains("[interrupted]"))
        assertTrue(manager.resumableSnapshots().single().id == "job-agent")
    }

    @Test
    fun structuredPersistentStartReturnsStableJobIdentity() = runTest {
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            idFactory = { "job-stable" },
        )

        val result = manager.startPersistentResult(
            label = "子代理：审计",
            resumeKind = "subagent_readonly",
            resumePayload = "{}",
            ownerId = "session-a",
            continuable = true,
        ) { _, _ -> "完成" }

        assertTrue(result.accepted)
        assertEquals("job-stable", result.id)
        assertTrue(result.message.contains("job-stable"))
        advanceUntilIdle()
    }

    @Test
    fun stableInboxMessageIdIsIdempotentButRejectsPayloadConflict() = runTest {
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                JobSnapshot(
                    id = "job-agent",
                    label = "子代理：审计",
                    status = "dormant",
                    resumeKind = "subagent_readonly",
                    resumePayload = "{}",
                    ownerId = "session-a",
                    continuable = true,
                ),
            ),
        )
        val message = QueuedAgentInput(
            id = "team-msg-1",
            content = "继续检查",
            memoryInput = "继续检查",
        )

        val first = manager.sendInput("job-agent", message, "session-a")
        val duplicate = manager.sendInput("job-agent", message, "session-a")
        val conflict = manager.sendInput(
            "job-agent",
            message.copy(content = "不同内容", memoryInput = "不同内容"),
            "session-a",
        )

        assertTrue(first.accepted)
        assertTrue(!first.duplicate)
        assertTrue(first.requiresResume)
        assertTrue(duplicate.accepted)
        assertTrue(duplicate.duplicate)
        assertTrue(!conflict.accepted)
        assertTrue(conflict.duplicate)
        assertEquals(listOf("team-msg-1"), manager.peekMessages("job-agent").map { it.id })
    }

    @Test
    fun callerSelectedPersistentIdIsStableAndCollisionFailsLoud() = runTest {
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
        )

        val first = manager.startPersistentResult(
            label = "子代理：team member",
            resumeKind = "subagent_readonly",
            resumePayload = "{}",
            ownerId = "session-a",
            continuable = true,
            requestedId = "job-team-fixed",
        ) { _, _ -> "done" }
        val second = manager.startPersistentResult(
            label = "子代理：team member 2",
            resumeKind = "subagent_readonly",
            resumePayload = "{}",
            ownerId = "session-a",
            continuable = true,
            requestedId = "job-team-fixed",
        ) { _, _ -> "never" }

        assertTrue(first.accepted)
        assertEquals("job-team-fixed", first.id)
        assertTrue(!second.accepted)
        assertTrue(second.message.contains("编号已存在"))
        advanceUntilIdle()
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

    @Test
    fun successfulExternalWorkMustNotBeLabelledFailedWhenOnlyTerminalReceiptCannotPersist() = runTest {
        var externalExecutionCount = 0
        var asyncFailure: Throwable? = null
        var durable = emptyList<JobSnapshot>()
        val workerScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> asyncFailure = error },
        )
        try {
            val manager = HarnessJobManager(
                scope = workerScope,
                onChanged = {},
                idFactory = { "successful-but-receipt-failed" },
                onSnapshotsChanged = { snapshots ->
                    // Start is durably recorded. Only the terminal receipt fails.
                    if (snapshots.singleOrNull()?.status == "running") {
                        durable = snapshots
                    } else {
                        error("disk full")
                    }
                },
            )
            manager.startPersistent(
                label = "用户的有副作用外部操作",
                resumeKind = "external_tool",
                resumePayload = "{}",
            ) { _, _ ->
                externalExecutionCount++
                "外部操作已执行成功"
            }
            advanceUntilIdle()
            assertEquals(1, externalExecutionCount)
            assertEquals("running", durable.single().status)
            assertTrue("终态无法持久化的故障必须显式暴露", asyncFailure != null)
            // Failed business work and failed receipt persistence are not the same fact.
            assertFalse("不能把已成功执行的外部操作误判为业务失败",
                manager.snapshots().single().status == "failed")
            assertTrue("终态结果必须标示无法核实",
                manager.snapshots().single().output.contains("未知"))
            assertTrue("即使回执故障也必须释放并发名额", manager.availableSlots() > 0)
        } finally {
            workerScope.cancel()
        }
    }

    @Test
    fun jobInfoProjectsDirectAgentUiCapabilities() = runTest {
        var latest = emptyList<JobInfo>()
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { latest = it },
            idFactory = { "job-agent" },
        )

        manager.startPersistent(
            label = "子代理：审计",
            resumeKind = "subagent_readonly",
            resumePayload = "{}",
            ownerId = "session-a",
            continuable = true,
        ) { _, _ -> "第一轮完成" }
        advanceUntilIdle()

        val dormant = latest.single()
        assertTrue(dormant.isAgent)
        assertTrue(dormant.canMessage)
        assertTrue(dormant.continuable)
        assertTrue(dormant.status == "dormant")
        assertTrue(dormant.pendingMessageCount == 0)

        manager.send("job-agent", "继续核查", "session-a")

        val queued = latest.single()
        assertTrue(queued.canMessage)
        assertTrue(queued.pendingMessageCount == 1)
    }

}
