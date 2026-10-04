package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease

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
    @Test
    fun foregroundAndAutomationShareOneSessionOwner() = runTest {
        var automationStarted = false
        val foreground = LocalSessionRuntimeRegistry.acquire("shared-owner", LocalSessionRuntimeKind.FOREGROUND)
        val automation = launch {
            LocalSessionRuntimeRegistry.withOwner("shared-owner", LocalSessionRuntimeKind.AUTOMATION_WORK) {
                automationStarted = true
            }
        }
        runCurrent()
        assertFalse(automationStarted)
        foreground.close()
        automation.join()
        assertTrue(automationStarted)
        assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner("shared-owner"))
    }
    @Test
    fun activeRuntimePreventsLoadTimeDurableRewrite() = runTest {
        val lease = LocalSessionRuntimeRegistry.acquire("load-write", LocalSessionRuntimeKind.AUTOMATION_CHAT)
        var writes = 0
        assertFalse(LocalSessionRuntimeRegistry.submitWhenIdle("load-write") { writes++ })
        lease.close()
        assertTrue(LocalSessionRuntimeRegistry.submitWhenIdle("load-write") { writes++ })
        assertEquals(1, writes)
    }
    @Test
    fun deletionOwnershipWaitsForAutomationAndBlocksNewOwners() = runTest {
        val sessionId = "delete-owned-session"
        val automation = LocalSessionRuntimeRegistry.acquire(
            sessionId,
            LocalSessionRuntimeKind.AUTOMATION_WORK,
        )
        var deletionLeases: List<LocalSessionRuntimeLease>? = null
        val waiter = launch {
            deletionLeases = LocalSessionRuntimeRegistry.acquireAll(
                listOf(sessionId),
                LocalSessionRuntimeKind.SESSION_DELETE,
            )
        }

        runCurrent()
        assertNull(deletionLeases)
        automation.close()
        waiter.join()

        assertNotNull(deletionLeases)
        assertTrue(LocalSessionRuntimeRegistry.hasLiveOwner(sessionId))
        assertNull(
            LocalSessionRuntimeRegistry.tryAcquire(
                sessionId,
                LocalSessionRuntimeKind.AUTOMATION_CHAT,
            ),
        )

        deletionLeases!!.asReversed().forEach(LocalSessionRuntimeLease::close)
        assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner(sessionId))
    }

    @Test
    fun nonBlockingAcquireDoesNotBypassQueuedReservation() = runTest {
        val sessionId = "fair-handoff-session"
        val first = LocalSessionRuntimeRegistry.acquire(
            sessionId,
            LocalSessionRuntimeKind.AUTOMATION_CHAT,
        )
        var waiterLease: LocalSessionRuntimeLease? = null
        val waiter = launch {
            waiterLease = LocalSessionRuntimeRegistry.acquire(
                sessionId,
                LocalSessionRuntimeKind.AUTOMATION_WORK,
            )
        }
        runCurrent()
        first.close()

        assertNull(
            LocalSessionRuntimeRegistry.tryAcquire(
                sessionId,
                LocalSessionRuntimeKind.MAINTENANCE,
            ),
        )
        runCurrent()
        assertNotNull(waiterLease)

        waiterLease!!.close()
        waiter.join()
        assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner(sessionId))
    }

}
