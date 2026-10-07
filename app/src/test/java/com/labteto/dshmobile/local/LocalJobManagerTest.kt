package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.jobs.JobInboxMessage
import com.labteto.dshmobile.harness.jobs.JobSnapshot
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.jobs.LocalPersistentJobStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalJobManagerTest {
    @Test
    fun historicalCommandLabelsDoNotRevealCredentialsInJobList() = runTest {
        val manager = LocalJobManager(this, onChanged = { })
        val token = "test-secret-123"
        val started = manager.start("curl -H 'Authorization: Bearer $token' https://example.test") { _, _ -> "ok" }
        assertTrue(manager.list().contains(started.substringAfterLast('：')))
        assertTrue(!manager.list().contains(token))
        assertTrue(!manager.output(started.substringAfterLast('：')).contains(token))
    }
    @Test
    fun exposesProgressBeforeJobCompletesAndThenPublishesFinalOutput() = runTest {
        val gate = CompletableDeferred<Unit>()
        val manager = LocalJobManager(this) { }
        val started = manager.start("streaming") { _, report ->
            report("partial output")
            gate.await()
            "final output"
        }
        val id = started.substringAfterLast('：')

        runCurrent()
        assertTrue(manager.output(id).contains("partial output"))
        assertTrue(manager.output(id).contains("[running]"))

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(manager.output(id).contains("final output"))
        assertTrue(manager.output(id).contains("[completed]"))
    }
    @Test
    fun jobIdsAreUniqueAndCancellingOneDoesNotCancelAnother() = runTest {
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        val manager = LocalJobManager(this) { }

        val first = manager.start("first") { _, _ ->
            gateA.await()
            "first done"
        }
        val second = manager.start("second") { _, _ ->
            gateB.await()
            "second done"
        }
        val firstId = first.substringAfterLast('：')
        val secondId = second.substringAfterLast('：')

        assertTrue(firstId != secondId)
        assertTrue(firstId.startsWith("job-"))
        assertTrue(secondId.startsWith("job-"))

        manager.kill(firstId)
        runCurrent()
        gateB.complete(Unit)
        advanceUntilIdle()

        assertTrue(manager.output(firstId).contains("[cancelled]"))
        assertTrue(manager.output(secondId).contains("[completed]"))
        assertTrue(manager.output(secondId).contains("second done"))
    }


    @Test
    fun stoppingOwnedNonPersistentJobsDoesNotTouchAnotherSessionOrPersistentJob() = runTest {
        val otherGate = CompletableDeferred<Unit>()
        val persistentGate = CompletableDeferred<Unit>()
        val manager = LocalJobManager(this) { }

        val transient = manager.start("a-transient", ownerSessionId = "session-a") { _, _ ->
            awaitCancellation()
        }
        val persistent = manager.startPersistent(
            label = "a-persistent",
            resumeKind = "web_fetch",
            resumePayload = "{}",
            ownerSessionId = "session-a",
        ) { _, _ ->
            persistentGate.await()
            "persistent done"
        }
        val other = manager.start("b-transient", ownerSessionId = "session-b") { _, _ ->
            otherGate.await()
            "other done"
        }
        val transientId = transient.substringAfterLast('：')
        val persistentId = persistent.substringAfterLast('：')
        val otherId = other.substringAfterLast('：')
        runCurrent()

        manager.stopOwnedNonPersistentAndJoin(setOf("session-a"))
        runCurrent()

        assertTrue(manager.output(transientId, "session-a").contains("[cancelled]"))
        assertTrue(manager.output(persistentId, "session-a").contains("[running]"))
        assertTrue(manager.output(otherId, "session-b").contains("[running]"))

        persistentGate.complete(Unit)
        otherGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun scopedJobApisCannotSeeOrControlAnotherSession() = runTest {
        val otherGate = CompletableDeferred<Unit>()
        val manager = LocalJobManager(this) { }

        val first = manager.start("first", ownerSessionId = "session-a") { _, _ -> awaitCancellation() }
        val second = manager.start("second", ownerSessionId = "session-b") { _, _ ->
            otherGate.await()
            "second done"
        }
        val firstId = first.substringAfterLast('：')
        val secondId = second.substringAfterLast('：')
        runCurrent()

        assertTrue(manager.list("session-a").contains(firstId))
        assertTrue(!manager.list("session-a").contains(secondId))
        assertTrue(manager.output(secondId, "session-a").contains("不存在"))
        assertTrue(manager.kill(secondId, "session-a").contains("不存在"))
        assertTrue(manager.output(secondId, "session-b").contains("[running]"))

        manager.kill(firstId, "session-a")
        otherGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(manager.output(secondId, "session-b").contains("[completed]"))
    }

    @Test
    fun corruptPrimaryRecoversPreviousPersistentSnapshotFromBackup() = runTest {
        val root = createTempDir(prefix = "persistent-jobs-backup-")
        try {
            val file = File(root, "jobs.json")
            val store = LocalPersistentJobStore(
                file = file,
                json = Json { ignoreUnknownKeys = true },
            )
            store.write(
                listOf(
                    JobSnapshot(
                        id = "job-first",
                        label = "第一代",
                        status = "completed",
                        output = "first",
                    ),
                ),
            )
            store.write(
                listOf(
                    JobSnapshot(
                        id = "job-second",
                        label = "第二代",
                        status = "completed",
                        output = "second",
                    ),
                ),
            )

            file.writeText("{broken")

            val recovered = store.read()

            assertEquals("job-first", recovered.single().id)
            assertEquals("first", recovered.single().output)
            assertTrue(store.read().single().id == "job-first")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptedNonPersistentJobReportsEarlyTerminationAgainstRequestedDeadline() = runTest {
        val root = createTempDir(prefix = "interrupted-shell-deadline-")
        try {
            val store = LocalPersistentJobStore(
                file = File(root, "jobs.json"),
                json = Json { ignoreUnknownKeys = true },
            )
            store.write(
                listOf(
                    JobSnapshot(
                        id = "job-shell",
                        label = "后台命令",
                        status = "running",
                        startedAt = 1_000L,
                        deadlineAt = 901_000L,
                        updatedAt = 721_000L,
                    ),
                ),
            )

            val restarted = LocalJobManager(this, store) { }
            val output = restarted.output("job-shell")

            assertTrue(output.contains("[interrupted]"))
            assertTrue(output.contains("在请求期限前中断"))
            assertTrue(output.contains("已运行约 720 秒"))
            assertTrue(output.contains("请求上限 900 秒"))
            val persisted = store.read().single()
            assertEquals(1_000L, persisted.startedAt)
            assertEquals(901_000L, persisted.deadlineAt)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyPersistentPayloadRecoversSessionOwnershipForDeletion() = runTest {
        val root = createTempDir(prefix = "legacy-persistent-job-owner-")
        try {
            val file = File(root, "jobs.json")
            val store = LocalPersistentJobStore(
                file = file,
                json = Json { ignoreUnknownKeys = true },
            )
            store.write(
                listOf(
                    JobSnapshot(
                        id = "job-legacy",
                        label = "网页抓取",
                        status = "running",
                        resumeKind = "web_fetch",
                        resumePayload = """{"session_id":"session-old","url":"https://example.com"}""",
                    ),
                ),
            )

            val restored = store.read().single()
            assertEquals("session-old", restored.ownerId)

            val manager = LocalJobManager(this, store) { }
            manager.removeOwnedAndJoin(setOf("session-old"))
            assertTrue(manager.snapshotInfos().none { it.id == "job-legacy" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun persistentStoreRoundTripsAgentInbox() = runTest {
        val root = createTempDir(prefix = "persistent-agent-inbox-")
        try {
            val store = LocalPersistentJobStore(
                file = File(root, "jobs.json"),
                json = Json { ignoreUnknownKeys = true },
            )
            store.write(
                listOf(
                    JobSnapshot(
                        id = "job-agent",
                        label = "子代理：审计",
                        status = "running",
                        resumeKind = "subagent_readonly",
                        resumePayload = """{"session_id":"session-a"}""",
                        ownerId = "session-a",
                        inbox = listOf(
                            JobInboxMessage("msg-1", "继续核查"),
                            JobInboxMessage("msg-2", "补充验证"),
                        ),
                        continuationState = """{"version":1,"step":3}""",
                    ),
                ),
            )

            val restored = store.read().single()

            assertEquals(listOf("msg-1", "msg-2"), restored.inbox.map { it.id })
            assertEquals(listOf("继续核查", "补充验证"), restored.inbox.map { it.content })
            assertEquals("""{"version":1,"step":3}""", restored.continuationState)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun persistentStoreRestoresRunningJobAsInterrupted() = runTest {
        val root = createTempDir(prefix = "persistent-jobs-")
        try {
            val file = File(root, "jobs.json")
            val store = LocalPersistentJobStore(
                file = file,
                json = Json { ignoreUnknownKeys = true },
            )
            store.write(
                listOf(
                    JobSnapshot(
                        id = "job-restored",
                        label = "网页抓取",
                        status = "running",
                        resumeKind = "web_fetch",
                        resumePayload = "{\"url\":\"https://example.com\"}",
                    ),
                ),
            )
            assertTrue(file.isFile)
            assertEquals("running", store.read().single().status)

            val restarted = LocalJobManager(this, store) { }
            assertTrue(restarted.list().contains("job-restored [interrupted]"))
            assertEquals("web_fetch", restarted.interruptedSnapshots().single().resumeKind)
        } finally {
            root.deleteRecursively()
        }
    }
}
