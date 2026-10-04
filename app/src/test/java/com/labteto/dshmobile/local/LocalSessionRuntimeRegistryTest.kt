package com.labteto.dshmobile.local

import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalSessionRuntimeRegistryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun liveAutomationOwnerPreventsGenericCrashTailRepair() = runTest {
        val log = LocalSessionEventLog(
            File(temporary.root, "session-a.events.jsonl"),
            json,
            sessionId = "session-a",
        )
        log.append("turn/start", buildJsonObject { put("automation", true) })

        val lease = LocalSessionRuntimeRegistry.acquire(
            "session-a",
            LocalSessionRuntimeKind.AUTOMATION_CHAT,
        )
        try {
            assertFalse(log.repairInterruptedTail().repaired)
            assertNull(log.latest("turn/end"))
        } finally {
            lease.close()
        }

        assertTrue(log.repairInterruptedTail().repaired)
        assertNotNull(log.latest("turn/end"))
    }

    @Test
    fun automationRecoveryCanForceRepairItsOwnPreviousTail() = runTest {
        val log = LocalSessionEventLog(
            File(temporary.root, "session-b.events.jsonl"),
            json,
            sessionId = "session-b",
        )
        log.append("turn/start", buildJsonObject { put("automation", true) })

        val lease = LocalSessionRuntimeRegistry.acquire(
            "session-b",
            LocalSessionRuntimeKind.AUTOMATION_WORK,
        )
        try {
            assertTrue(log.repairInterruptedTail(force = true).repaired)
            assertNotNull(log.latest("turn/end"))
        } finally {
            lease.close()
        }
    }
    @Test
    fun completedAndCancelledReservationsReleaseSessionEntries() = runTest {
        val before = LocalSessionRuntimeRegistry.retainedSessionCount()
        val first = LocalSessionRuntimeRegistry.acquire("queued-session", LocalSessionRuntimeKind.AUTOMATION_CHAT)
        val waiter = launch {
            LocalSessionRuntimeRegistry.acquire("queued-session", LocalSessionRuntimeKind.AUTOMATION_WORK).close()
        }
        runCurrent()
        waiter.cancel()
        waiter.join()
        assertTrue(LocalSessionRuntimeRegistry.hasLiveOwner("queued-session"))
        first.close()
        first.close()
        repeat(100) {
            LocalSessionRuntimeRegistry.acquire("released-$it", LocalSessionRuntimeKind.AUTOMATION_CHAT).close()
        }
        assertEquals(before, LocalSessionRuntimeRegistry.retainedSessionCount())
    }

    @Test
    fun queuedOwnerKeepsSameMutexUntilRelease() = runTest {
        val first = LocalSessionRuntimeRegistry.acquire("handoff-session", LocalSessionRuntimeKind.AUTOMATION_CHAT)
        var acquired = false
        val waiter = launch {
            val lease = LocalSessionRuntimeRegistry.acquire("handoff-session", LocalSessionRuntimeKind.AUTOMATION_WORK)
            acquired = true
            assertTrue(LocalSessionRuntimeRegistry.hasLiveOwner("handoff-session"))
            lease.close()
        }
        runCurrent()
        assertFalse(acquired)
        first.close()
        waiter.join()
        assertTrue(acquired)
        assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner("handoff-session"))
    }
}
