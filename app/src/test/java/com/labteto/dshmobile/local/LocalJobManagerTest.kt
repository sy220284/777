package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.jobs.JobSnapshot
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
