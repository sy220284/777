package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.LocalWorkRunBinding
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkRunRegistryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun registryOwnsAttachLiveAndDetachLifecycle() {
        val registry = LocalWorkRunRegistry()
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
        val registry = LocalWorkRunRegistry()
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
            initialState = LocalHarnessState(),
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
