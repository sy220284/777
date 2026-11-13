package com.labteto.dshmobile.local.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentExecutionBackendTest {
    private fun grant(epoch: Long = 5L) = LocalRemoteAgentGrant(
        sessionId = "user-session", delegationId = "parent-run:tool-id",
        authenticatedPeerFingerprint = "a".repeat(64),
        approvalEventSequence = 29L,
        rootUserRevision = 23L,
        leaseEpoch = epoch,
        taskFingerprint = "b".repeat(64),
        expiryEpochMillis = 10_000L,
        permittedToolIds = setOf("read"),
    )
    private fun receipt(epoch: Long = 5L, seq: Long = 1L) = LocalRemoteAgentReceipt(
        sessionId = "user-session", delegationId = "parent-run:tool-id",
        authenticatedPeerFingerprint = "a".repeat(64),
        leaseEpoch = epoch,
        remoteSequence = seq,
        taskFingerprint = "b".repeat(64),
        claimsCompleted = true,
    )
    private fun check(
        grant: LocalRemoteAgentGrant,
        receipt: LocalRemoteAgentReceipt,
        committed: Long,
        auth: Boolean = true,
        approved: Boolean = true,
        now: Long = 50L,
    ) = reconcileRemoteAgentReceipt(
        grant, receipt, committed, auth, approved, now,
    )

    @Test fun backendSelectionSurvivesPersistentCapabilityCodec() {
        for (backend in LocalAgentExecutionBackend.entries) {
            val original = LocalSubagentCapabilities(executionBackend = backend,
                remoteBackendRevision = if (backend == LocalAgentExecutionBackend.TRUSTED_REMOTE) "pinned-peer-v1" else null)
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("capabilities", encodeLocalSubagentCapabilities(original))
            }
            assertEquals(original, decodeLocalSubagentCapabilities(payload, 2))
        }
        assertTrue(runCatching { LocalAgentExecutionBackend.fromWire("fake-cloud") }.isFailure)
    }

    @Test fun reconnectedPeerCannotReplayOldLeaseOrDuplicateEffects() {
        assertEquals(LocalRemoteReceiptDisposition.ACCEPT_FRESH_EVIDENCE,
            check(grant(), receipt(), 0L))
        assertEquals(LocalRemoteReceiptDisposition.IDEMPOTENT_DUPLICATE,
            check(grant(), receipt(), 1L))
        assertEquals(LocalRemoteReceiptDisposition.REJECT_STALE_LEASE,
            check(grant(6), receipt(5), 0L))
        assertEquals(LocalRemoteReceiptDisposition.REJECT_STALE_LEASE,
            check(grant(), receipt(), 0L, now = 10_000L))
    }

    @Test fun missingWatermarkAndNetworkPartitionCannotBecomeSuccessfulCompletion() {
        assertEquals(LocalRemoteReceiptDisposition.WAIT_FOR_RECOVERY,
            check(grant(), receipt(seq = 3L), 1L))
        assertEquals(LocalRemoteReceiptDisposition.WAIT_FOR_RECOVERY,
            check(grant(), receipt(), -1L))
        assertEquals(LocalRemoteReceiptDisposition.REJECT_UNAUTHENTICATED_OR_CHANGED_TASK,
            check(grant(), receipt(), 0L, auth = false))
        assertEquals(LocalRemoteReceiptDisposition.REJECT_UNAUTHENTICATED_OR_CHANGED_TASK,
            check(grant(), receipt(), 0L, approved = false))
        assertEquals(LocalRemoteReceiptDisposition.REJECT_UNAUTHENTICATED_OR_CHANGED_TASK,
            check(grant(), receipt().copy(taskFingerprint = "c".repeat(64)), 0L))
    }

    @Test fun approvalAndSecretExportCannotBeManufacturedByModelFields() {
        assertTrue(runCatching { grant().copy(transportsUserSecrets = true) }.isFailure)
        assertTrue(runCatching { grant().copy(authenticatedPeerFingerprint = "foo") }.isFailure)
        assertTrue(runCatching { grant().copy(approvalEventSequence = -1L) }.isFailure)
        assertTrue(runCatching { grant().copy(permittedToolIds = setOf("credential_dump")) }.isFailure)
    }
}
