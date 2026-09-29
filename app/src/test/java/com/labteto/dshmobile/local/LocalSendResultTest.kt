package com.labteto.dshmobile.local

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
}
