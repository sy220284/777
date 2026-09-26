package com.labteto.dshmobile.local

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSessionCoordinatorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun legacyFullSnapshotRestoresOnlyBoundedRuntimeWindow() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val sessions = temporary.newFolder("sessions")
        val repository = LocalSessionRepository(sessions, json, backgroundScope, {}, {})
        val coordinator = LocalSessionCoordinator(
            repository = repository,
            eventLogFor = { id -> LocalSessionEventLog(File(sessions, "$id.events.jsonl"), json) },
            runtimeWindowMessages = 2,
        )
        val legacy = LocalHarnessSession(
            id = "legacy",
            messages = (0 until 5).map { index ->
                message("m$index", if (index % 2 == 0) "user" else "assistant", "消息$index")
            },
            transcriptIndex = LocalTranscriptRuntimeIndex(),
        )

        val restored = coordinator.restoreTranscript(legacy, persistedSnapshotExists = true)

        assertEquals(listOf("m3", "m4"), restored.messages.map { it.id })
        assertEquals(5L, restored.index.totalMessageCount)
        assertTrue(restored.needsPersist)
    }

    @Test
    fun snapshotNeverPersistsCompleteRuntimeMessages() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val sessions = temporary.newFolder("snapshot-sessions")
        val repository = LocalSessionRepository(sessions, json, backgroundScope, {}, {})
        val coordinator = LocalSessionCoordinator(
            repository = repository,
            eventLogFor = { id -> LocalSessionEventLog(File(sessions, "$id.events.jsonl"), json) },
            runtimeWindowMessages = 2,
        )
        val state = LocalHarnessState(
            loading = false,
            sessionId = "s1",
            messages = listOf(
                message("m1", "user", "一"),
                message("m2", "assistant", "二"),
                message("m3", "user", "三"),
            ),
            transcriptIndex = LocalTranscriptRuntimeIndex(totalMessageCount = 3L),
        )

        val snapshot = coordinator.snapshot(
            sessionId = "s1",
            state = state,
            controlProjectedThroughSequence = 8L,
            transcriptProjectedThroughSequence = 7L,
        )

        assertTrue(snapshot.messages.isEmpty())
        assertEquals(listOf("m2", "m3"), snapshot.transcriptWindow.map { it.id })
        assertEquals(3L, snapshot.transcriptIndex.totalMessageCount)
        assertEquals(8L, snapshot.controlProjectedThroughSequence)
        assertEquals(7L, snapshot.transcriptProjectedThroughSequence)
    }

    private fun message(id: String, role: String, content: String) = LocalHarnessMessage(
        id = id,
        role = role,
        content = content,
        createdAt = id.hashCode().toLong(),
    )
}
