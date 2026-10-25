package com.labteto.dshmobile.local.agent

/**
 * E13 multi-backend admission protocol. The only executable backend today is
 * IN_PROCESS. A remote peer is NEVER inferred from a model name, URL or prompt:
 * selecting an unsupported backend fails before creating a Job or contacting a peer.
 */
internal enum class LocalAgentExecutionBackend(val wireName: String) {
    IN_PROCESS("in_process"),
    ISOLATED_PROCESS("isolated_process"),
    TRUSTED_REMOTE("trusted_remote");

    companion object {
        fun fromWire(raw: String?): LocalAgentExecutionBackend = when (raw?.trim()?.lowercase()) {
            null, "", "in_process" -> IN_PROCESS
            "isolated_process" -> ISOLATED_PROCESS
            "trusted_remote" -> TRUSTED_REMOTE
            else -> throw IllegalArgumentException("AGENT_BACKEND_UNKNOWN")
        }
    }
}

internal fun requireExecutableAgentBackend(backend: LocalAgentExecutionBackend) {
    require(backend == LocalAgentExecutionBackend.IN_PROCESS) {
        "AGENT_BACKEND_NOT_CONFIGURED: ${backend.wireName}; no authenticated transport or isolated-process owner registered"
    }
}

/**
 * Contract for a future authenticated transport. Only the transport after actual
 * mutual authentication and explicit USER approval may construct a verified proof.
 * The tool/schema parser cannot grant these properties.
 */
internal data class LocalRemoteAgentGrant(
    val sessionId: String,
    val delegationId: String,
    val authenticatedPeerFingerprint: String,
    val approvalEventSequence: Long,
    val rootUserRevision: Long,
    val leaseEpoch: Long,
    val taskFingerprint: String,
    val expiryEpochMillis: Long,
    val permittedToolIds: Set<String>,
    val transportsUserSecrets: Boolean = false,
) {
    init {
        require(sessionId.isNotBlank() && delegationId.isNotBlank()) { "REMOTE_AGENT_ID_REQUIRED" }
        require(authenticatedPeerFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "REMOTE_AGENT_PEER_UNVERIFIED"
        }
        require(taskFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "REMOTE_AGENT_TASK_FINGERPRINT_INVALID"
        }
        require(approvalEventSequence >= 0L && rootUserRevision >= -1L && leaseEpoch > 0L &&
            expiryEpochMillis > 0L) { "REMOTE_AGENT_GRANT_INVALID" }
        require(!transportsUserSecrets && permittedToolIds.none { it.isBlank() || it.contains("credential", true) }) {
            "REMOTE_AGENT_SECRET_EXPORT_DENIED"
        }
    }
}

/** State is an event-projected receipt, not a second scheduler or write-ahead job owner. */
internal data class LocalRemoteAgentReceipt(
    val sessionId: String,
    val delegationId: String,
    val authenticatedPeerFingerprint: String,
    val leaseEpoch: Long,
    val remoteSequence: Long,
    val taskFingerprint: String,
    val claimsCompleted: Boolean,
)

internal enum class LocalRemoteReceiptDisposition {
    ACCEPT_FRESH_EVIDENCE, IDEMPOTENT_DUPLICATE, REJECT_STALE_LEASE,
    REJECT_UNAUTHENTICATED_OR_CHANGED_TASK, WAIT_FOR_RECOVERY,
}

/**
 * Decide before committing an untrusted network receipt. A partition or missing
 * contiguous sequence means UNKNOWN, never "failed" or "completed"; duplicated
 * receipts cannot apply the same outside-world write twice.
 */
internal fun reconcileRemoteAgentReceipt(
    grant: LocalRemoteAgentGrant,
    receipt: LocalRemoteAgentReceipt,
    lastCommittedRemoteSequence: Long,
    authenticatedTransport: Boolean,
    userApprovalStillCurrent: Boolean,
    nowEpochMillis: Long,
): LocalRemoteReceiptDisposition {
    if (!authenticatedTransport || !userApprovalStillCurrent ||
        receipt.sessionId != grant.sessionId || receipt.delegationId != grant.delegationId ||
        receipt.authenticatedPeerFingerprint != grant.authenticatedPeerFingerprint ||
        receipt.taskFingerprint != grant.taskFingerprint) {
        return LocalRemoteReceiptDisposition.REJECT_UNAUTHENTICATED_OR_CHANGED_TASK
    }
    if (receipt.leaseEpoch != grant.leaseEpoch || nowEpochMillis >= grant.expiryEpochMillis) {
        return LocalRemoteReceiptDisposition.REJECT_STALE_LEASE
    }
    if (lastCommittedRemoteSequence < 0L || receipt.remoteSequence < 0L) {
        return LocalRemoteReceiptDisposition.WAIT_FOR_RECOVERY
    }
    if (receipt.remoteSequence <= lastCommittedRemoteSequence) {
        return LocalRemoteReceiptDisposition.IDEMPOTENT_DUPLICATE
    }
    if (receipt.remoteSequence != lastCommittedRemoteSequence + 1L) {
        return LocalRemoteReceiptDisposition.WAIT_FOR_RECOVERY
    }
    return LocalRemoteReceiptDisposition.ACCEPT_FRESH_EVIDENCE
}
