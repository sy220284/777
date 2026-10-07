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
