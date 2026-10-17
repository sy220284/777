package com.labteto.dshmobile.harness.session

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionExecutionRegistryTest {
    private val execution = SessionExecutionOwner("execution", acceptsInput = true)
    private val deletion = SessionExecutionOwner("deletion", acceptsInput = false)

    @Test
    fun releasedIdentityCannotCommitAfterSameSessionIsReacquired() = runTest {
        val registry = SessionExecutionRegistry()
        val first = registry.acquire("session", execution)
        first.close()
        val next = registry.acquire("session", execution)
        try {
            assertEquals(first.identity.incarnation, next.identity.incarnation)
            assertTrue(next.identity.epoch > first.identity.epoch)
            assertThrows(IllegalStateException::class.java) { first.requireCurrent() }
            first.close()
            next.requireCurrent()
        } finally { next.close() }
        assertEquals(0, registry.retainedSessionCount())
    }

    @Test
    fun ownershipCannotBeBorrowedForAnotherSessionKindOrRegistry() = runTest {
        val registry = SessionExecutionRegistry()
        val foreign = SessionExecutionRegistry()
        val lease = registry.acquire("session", execution)
        try {
            assertThrows(IllegalStateException::class.java) {
                registry.requireOwner(lease, "other-session", execution)
            }
            assertThrows(IllegalStateException::class.java) {
                registry.requireOwner(lease, "session", deletion)
            }
            assertThrows(IllegalStateException::class.java) {
                foreign.requireOwner(lease, "session", execution)
            }
            registry.requireOwner(lease, "session", execution)
        } finally { lease.close() }
    }

    @Test
    fun bulkAdmissionRejectsBlankSessionBeforeAcquiringAnyOwner() = runTest {
        val registry = SessionExecutionRegistry()
        val failed = runCatching {
            registry.acquireAll(listOf("valid", "  ", "another"), deletion)
        }
        assertTrue(failed.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, registry.retainedSessionCount())
        assertFalse(registry.hasAnyLiveOwner())
        val leases = registry.acquireAll(listOf("b", "a", "b"), deletion)
        try {
            assertEquals(listOf("a", "b"), leases.map { it.identity.sessionId })
        } finally {
            leases.asReversed().forEach(SessionExecutionLease::close)
        }
        assertEquals(0, registry.retainedSessionCount())
    }

    @Test
    fun waitingSessionReservationCannotBePresentedAsIdleDuringOwnerHandoff() = runTest {
        val registry = SessionExecutionRegistry()
        val current = registry.acquire("handoff", execution)
        var next: SessionExecutionLease? = null
        val waiting = launch { next = registry.acquire("handoff", deletion) }
        runCurrent() // queue deletion while the foreground lease is still owned
        assertTrue(registry.hasLiveOwner("handoff"))
        current.close() // suspended waiter has not yet executed its claim callback
        val admission = registry.inputAdmission("handoff")
        assertTrue("a pending owner reservation still owns admission", admission.ownerActive)
        assertTrue("no active input receiver exists between claims", admission.inputBlocked)
        assertTrue(registry.hasLiveOwner("handoff"))
        assertNull(registry.tryAcquire("handoff", execution))
        runCurrent()
        try {
            assertTrue(registry.inputAdmission("handoff").inputBlocked)
            assertTrue(next != null)
            next!!.requireCurrent()
        } finally {
            next?.close()
            waiting.join()
        }
        assertFalse(registry.hasLiveOwner("handoff"))
        assertEquals(0, registry.retainedSessionCount())
    }

    @Test
    fun pendingDeletionBlocksNewInputEvenWhileForegroundOwnerStillAcceptsSteering() = runTest {
        val registry = SessionExecutionRegistry()
        val foreground = registry.acquire("session", execution)
        assertTrue(registry.inputAdmission("session").ownerActive)
        assertFalse(registry.inputAdmission("session").inputBlocked)

        var deletionLease: SessionExecutionLease? = null
        val waiting = launch { deletionLease = registry.acquire("session", deletion) }
        runCurrent()
        // The foreground owner has not released yet, but a destructive owner has
        // queued. New input must not extend the life of a session being deleted.
        assertTrue(registry.inputAdmission("session").inputBlocked)
        assertNull(registry.tryAcquire("session", execution))
        foreground.requireCurrent()
        foreground.close()
        runCurrent()
        try {
            assertTrue(registry.inputAdmission("session").inputBlocked)
            deletionLease!!.requireCurrent()
        } finally {
            deletionLease!!.close()
            waiting.join()
        }
        assertFalse(registry.hasLiveOwner("session"))
        assertEquals(0, registry.retainedSessionCount())
    }

    @Test
    fun cancelledPendingDeletionRestoresInputAdmissionToLiveForegroundOwner() = runTest {
        val registry = SessionExecutionRegistry()
        val foreground = registry.acquire("session", execution)
        val pending = launch { registry.acquire("session", deletion).close() }
        runCurrent()
        assertTrue(registry.inputAdmission("session").inputBlocked)
        pending.cancel()
        pending.join()
        // Cancellation must remove the pending exclusive reservation, otherwise
        // the still-legitimate owner would be stuck rejecting user steering.
        assertTrue(registry.inputAdmission("session").ownerActive)
        assertFalse(registry.inputAdmission("session").inputBlocked)
        foreground.requireCurrent()
        foreground.close()
        assertEquals(0, registry.retainedSessionCount())
    }

    @Test
    fun queuedDeletionCannotBeBypassedAndCancelledWaiterDoesNotLeak() = runTest {
        val registry = SessionExecutionRegistry()
        val first = registry.acquire("session", execution)
        var deleteLease: SessionExecutionLease? = null
        val waiter = launch { deleteLease = registry.acquire("session", deletion) }
        val cancelled = launch { registry.acquire("session", execution).close() }
        runCurrent()
        cancelled.cancel()
        cancelled.join()
        first.close()
        assertNull(registry.tryAcquire("session", execution))
        waiter.join()
        try {
            assertTrue(registry.inputAdmission("session").inputBlocked)
            assertNotEquals(first.identity, deleteLease!!.identity)
        } finally { deleteLease!!.close() }
        assertFalse(registry.hasAnyLiveOwner())
        assertEquals(0, registry.retainedSessionCount())
    }
}
