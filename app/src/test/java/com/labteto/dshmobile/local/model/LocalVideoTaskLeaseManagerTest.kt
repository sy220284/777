package com.labteto.dshmobile.local.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalVideoTaskLeaseManagerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun sameTaskWaitsForExistingWriterAndUserDeletionNeverRaces() = runBlocking {
        val lease = LocalVideoTaskLeaseManager(temp.newFolder("video-lease"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = launch(Dispatchers.IO) {
            lease.withLease("task-123") {
                started.complete(Unit)
                release.await()
            }
        }
        try {
            withTimeout(5_000) { started.await() }
            assertThrows(IllegalStateException::class.java) {
                lease.withExclusiveDeletion("task-123") { error("must never erase live transfer") }
            }
            val competing = async(Dispatchers.IO) {
                lease.withLease("task-123") { "second writer committed" }
            }
            assertFalse(competing.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { active.join() }
            assertEquals("second writer committed", withTimeout(5_000) { competing.await() })
            assertEquals("cleared", lease.withExclusiveDeletion("task-123") { "cleared" })
            assertTrue(temp.root.walk().any { it.name.endsWith(".writer.lock") })
        } finally {
            release.complete(Unit)
            active.cancel()
        }
    }

    @Test fun anotherTaskDoesNotWaitForAnUnrelatedVideo() = runBlocking {
        val lease = LocalVideoTaskLeaseManager(temp.newFolder("video-parallel"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holding = launch(Dispatchers.IO) {
            lease.withLease("first") {
                started.complete(Unit)
                release.await()
            }
        }
        try {
            withTimeout(5_000) { started.await() }
            assertEquals("independent", withTimeout(5_000) {
                lease.withLease("second") { "independent" }
            })
        } finally {
            release.complete(Unit)
            holding.join()
        }
    }
}
