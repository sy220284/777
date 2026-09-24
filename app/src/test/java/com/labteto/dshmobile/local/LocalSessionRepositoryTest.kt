package com.labteto.dshmobile.local

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalSessionRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun coalescesSnapshotsWithoutDroppingOtherSessions() = runTest {
        val failures = mutableListOf<Throwable>()
        val repository = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, failures::add)
        repository.enqueue(LocalHarnessSession(id = "first", title = "old"))
        repository.enqueue(
            LocalHarnessSession(
                id = "second",
                title = "other",
                usageMode = LocalUsageMode.CHAT,
                messages = listOf(LocalHarnessMessage("m1", "user", "hello", createdAt = 1L)),
            ),
        )
        repository.enqueue(LocalHarnessSession(id = "first", title = "new"))
        runCurrent()
        val reopened = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, failures::add)
        assertEquals("new", reopened.read("first")!!.title)
        assertEquals("other", reopened.read("second")!!.title)
        assertEquals(LocalUsageMode.CHAT, reopened.read("second")!!.usageMode)
        assertEquals(setOf("first", "second"), reopened.summaries().map { it.id }.toSet())
        val summaries = reopened.summaries().associateBy { it.id }
        assertEquals(LocalUsageMode.WORK, summaries.getValue("first").usageMode)
        assertEquals(LocalUsageMode.CHAT, summaries.getValue("second").usageMode)
        assertTrue(summaries.getValue("first").blank)
        assertFalse(summaries.getValue("second").blank)
        assertTrue(failures.isEmpty())
    }

    @Test fun legacySessionWithoutUsageModeDefaultsToWork() {
        val legacy = Json.decodeFromString(
            LocalHarnessSession.serializer(),
            """{"id":"legacy","title":"old","updatedAt":1}""",
        )
        assertEquals(LocalUsageMode.WORK, legacy.usageMode)
    }

    @Test fun failedWriteRetriesTheLatestSnapshotAfterStorageRecovers() = runTest {
        val root = temporary.newFile("blocked-sessions")
        val failures = mutableListOf<Throwable>()
        val repository = LocalSessionRepository(root, Json, backgroundScope, {}, failures::add)
        repository.enqueue(LocalHarnessSession(id = "first", title = "before"))
        runCurrent()
        assertEquals(1, failures.size)

        repository.enqueue(LocalHarnessSession(id = "first", title = "latest"))
        assertTrue(root.delete())
        assertTrue(root.mkdir())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("latest", repository.read("first")!!.title)
    }

    @Test fun summaryCacheTracksFreshWritesAfterInitialLoad() = runTest {
        val failures = mutableListOf<Throwable>()
        val repository = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, failures::add)
        repository.enqueue(LocalHarnessSession(id = "first", title = "before"))
        runCurrent()

        assertEquals("before", repository.summaries().single().title)

        repository.enqueue(
            LocalHarnessSession(
                id = "first",
                title = "after",
                messages = listOf(LocalHarnessMessage("m1", "user", "hello", createdAt = 1L)),
            ),
        )
        runCurrent()

        val summary = repository.summaries().single()
        assertEquals("after", summary.title)
        assertFalse(summary.blank)
        assertTrue(failures.isEmpty())
    }

}
