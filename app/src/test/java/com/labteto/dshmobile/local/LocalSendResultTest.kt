package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.send.evaluateLocalSendAdmission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSendResultTest {
    @Test
    fun sessionTransitionRejectsAndPreservesInputSemantics() {
        val result = evaluateLocalSendAdmission(
            configured = true,
            loading = false,
            sessionTransitioning = true,
            activeRun = false,
            pendingCount = 0,
            pendingLimit = 16,
        )

        assertEquals(LocalSendRejectReason.SESSION_TRANSITION, result?.rejectReason)
        assertFalse(result?.accepted ?: true)
        assertTrue(result?.message.orEmpty().contains("输入已保留"))
    }

    @Test
    fun queueFullRejectsInsteadOfPretendingTheMessageWasAccepted() {
        val result = evaluateLocalSendAdmission(
            configured = true,
            loading = false,
            sessionTransitioning = false,
            activeRun = true,
            pendingCount = 16,
            pendingLimit = 16,
        )

        assertEquals(LocalSendRejectReason.QUEUE_FULL, result?.rejectReason)
        assertFalse(result?.accepted ?: true)
        assertTrue(result?.message.orEmpty().contains("16"))
    }

    @Test
    fun runningTurnWithAvailableQueueSlotIsAdmitted() {
        val result = evaluateLocalSendAdmission(
            configured = true,
            loading = false,
            sessionTransitioning = false,
            activeRun = true,
            pendingCount = 15,
            pendingLimit = 16,
        )

        assertNull(result)
        assertTrue(LocalSendResult.Queued.accepted)
        assertTrue(LocalSendResult.Started.accepted)
    }

    @Test
    fun loadingAndMissingConfigurationAreExplicitRejections() {
        val loading = evaluateLocalSendAdmission(
            configured = true,
            loading = true,
            sessionTransitioning = false,
            activeRun = false,
            pendingCount = 0,
            pendingLimit = 16,
        )
        val unconfigured = evaluateLocalSendAdmission(
            configured = false,
            loading = false,
            sessionTransitioning = false,
            activeRun = false,
            pendingCount = 0,
            pendingLimit = 16,
        )

        assertEquals(LocalSendRejectReason.LOADING, loading?.rejectReason)
        assertEquals(LocalSendRejectReason.UNCONFIGURED, unconfigured?.rejectReason)
    }

    @Test
    fun failedQueueOfferDoesNotRunAcceptedOrQueuedCallbacks() {
        var accepted = 0
        var queued = 0
        var rejected = 0
        var started = 0

        val result = com.labteto.dshmobile.local.send.coordinateLocalSend(
            configured = true,
            loading = false,
            sessionTransitioning = false,
            activeRun = true,
            pendingCount = 0,
            pendingLimit = 16,
            onRejected = { rejected += 1 },
            onAccepted = { accepted += 1 },
            enqueue = { false },
            onQueued = { queued += 1 },
            onStart = { started += 1 },
        )

        assertFalse(result.accepted)
        assertEquals(LocalSendRejectReason.QUEUE_FULL, result.rejectReason)
        assertEquals(0, accepted)
        assertEquals(0, queued)
        assertEquals(0, started)
        assertEquals(1, rejected)
    }

    @Test
    fun idleSendStartsDirectlyWithoutTouchingQueue() {
        var accepted = 0
        var enqueueCalls = 0
        var queued = 0
        var started = 0

        val result = com.labteto.dshmobile.local.send.coordinateLocalSend(
            configured = true,
            loading = false,
            sessionTransitioning = false,
            activeRun = false,
            pendingCount = 0,
            pendingLimit = 16,
            onRejected = { error("不应拒绝") },
            onAccepted = { accepted += 1 },
            enqueue = {
                enqueueCalls += 1
                true
            },
            onQueued = { queued += 1 },
            onStart = { started += 1 },
        )

        assertTrue(result.accepted)
        assertEquals(LocalSendResult.Started, result)
        assertEquals(1, accepted)
        assertEquals(0, enqueueCalls)
        assertEquals(0, queued)
        assertEquals(1, started)
    }

}
