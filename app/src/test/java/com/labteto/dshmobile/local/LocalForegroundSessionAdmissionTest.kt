package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.send.LocalSendDisposition
import com.labteto.dshmobile.local.session.coordinateOwnedLocalSend
import com.labteto.dshmobile.local.session.reserveForegroundSendOwnership
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalForegroundSessionAdmissionTest {
    @Test
    fun idleWorkSendReservesSessionBeforeMutation() {
        val sessionId = "work-" + UUID.randomUUID()
        val ownership = reserveForegroundSendOwnership(
            usageMode = LocalUsageMode.WORK,
            sessionId = sessionId,
            workBindingActive = false,
            visibleJobActive = false,
            sessionTransitioning = false,
        )

        assertFalse(ownership.activeRun)
        assertNotNull(ownership.reservedWorkLease)
        assertTrue(LocalSessionRuntimeRegistry.hasLiveOwner(sessionId))

        ownership.reservedWorkLease!!.close()
        assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner(sessionId))
    }

    @Test
    fun automationOwnerForcesForegroundWorkIntoQueue() = runTest {
        val sessionId = "owned-" + UUID.randomUUID()
        val automation = LocalSessionRuntimeRegistry.acquire(
            sessionId,
            LocalSessionRuntimeKind.AUTOMATION_WORK,
        )
        try {
            val ownership = reserveForegroundSendOwnership(
                usageMode = LocalUsageMode.WORK,
                sessionId = sessionId,
                workBindingActive = false,
                visibleJobActive = false,
                sessionTransitioning = false,
            )
            assertTrue(ownership.activeRun)
            assertNull(ownership.reservedWorkLease)
        } finally {
            automation.close()
        }
    }

    @Test
    fun rejectedSendReleasesReservedWorkLease() {
        val sessionId = "rejected-" + UUID.randomUUID()
        val result = coordinateOwnedLocalSend(
            usageMode = LocalUsageMode.WORK,
            sessionId = sessionId,
            workBindingActive = false,
            visibleJobActive = false,
            configured = false,
            loading = false,
            sessionTransitioning = false,
            pendingCount = 0,
            pendingLimit = 8,
            onRejected = {},
            onAccepted = {},
            enqueue = { true },
            onQueued = {},
            onStart = {},
        )

        assertTrue(result.disposition == LocalSendDisposition.REJECTED)
        assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner(sessionId))
    }

    @Test
    fun failingAdmissionCallbacksCannotLeakReservedSessionOwner() {
        for (stage in listOf("rejected", "accepted", "start")) {
            val sessionId = "failed-$stage-" + UUID.randomUUID()
            val failure = IllegalStateException("disk failure")
            val result = runCatching {
                coordinateOwnedLocalSend(
                    usageMode = LocalUsageMode.WORK,
                    sessionId = sessionId,
                    workBindingActive = false,
                    visibleJobActive = false,
                    configured = stage != "rejected",
                    loading = false,
                    sessionTransitioning = false,
                    pendingCount = 0,
                    pendingLimit = 8,
                    onRejected = { if (stage == "rejected") throw failure },
                    onAccepted = { if (stage == "accepted") throw failure },
                    enqueue = { true },
                    onQueued = {},
                    onStart = { if (stage == "start") throw failure },
                )
            }
            assertTrue(result.exceptionOrNull() === failure)
            assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner(sessionId))
            val next = LocalSessionRuntimeRegistry.tryAcquire(sessionId, LocalSessionRuntimeKind.FOREGROUND)
            assertNotNull(next)
            next!!.close()
        }
    }

    @Test
    fun startedWorkSendHandsReservationToRunOwner() {
        val sessionId = "started-" + UUID.randomUUID()
        var handedOff: LocalSessionRuntimeLease? = null
        val result = coordinateOwnedLocalSend(
            usageMode = LocalUsageMode.WORK,
            sessionId = sessionId,
            workBindingActive = false,
            visibleJobActive = false,
            configured = true,
            loading = false,
            sessionTransitioning = false,
            pendingCount = 0,
            pendingLimit = 8,
            onRejected = {},
            onAccepted = {},
            enqueue = { true },
            onQueued = {},
            onStart = { handedOff = it },
        )

        assertTrue(result.disposition == LocalSendDisposition.STARTED)
        assertNotNull(handedOff)
        assertTrue(LocalSessionRuntimeRegistry.hasLiveOwner(sessionId))

        handedOff!!.close()
        assertFalse(LocalSessionRuntimeRegistry.hasLiveOwner(sessionId))
    }
}
