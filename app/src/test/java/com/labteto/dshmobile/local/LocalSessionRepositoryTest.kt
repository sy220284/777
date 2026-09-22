package com.labteto.dshmobile.local

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
                messages = listOf(LocalHarnessMessage("m1", "user", "hello", createdAt = 1L)),
            ),
        )
        repository.enqueue(LocalHarnessSession(id = "first", title = "new"))
        runCurrent()
        val reopened = LocalSessionRepository(temporary.root, Json, backgroundScope, {}, failures::add)
        assertEquals("new", reopened.read("first")!!.title)
        assertEquals("other", reopened.read("second")!!.title)
        assertEquals(setOf("first", "second"), reopened.summaries().map { it.id }.toSet())
        val summaries = reopened.summaries().associateBy { it.id }
        assertTrue(summaries.getValue("first").blank)
        assertFalse(summaries.getValue("second").blank)
        assertTrue(failures.isEmpty())
    }
}
