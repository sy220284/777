package com.labteto.dshmobile.local.work

/**
 * A single, stateless integration gate over the canonical Work completion facts.
 *
 * Codex-style review outcomes, Claude-style independent reviewer results and 777's
 * durable tool/artifact/test receipts serve different purposes. This gate never creates
 * another mutable ledger and cannot treat a model review as proof of a passing test.
 */
internal enum class LocalWorkIntegrationDisposition {
    REVIEW_REJECTED,
    REVIEW_REQUIRED,
    MACHINE_EVIDENCE_MISSING,
    OUTSTANDING_WORK,
    READY_FOR_GOAL_ADMISSION,
}

internal data class LocalWorkIntegrationDecision(
    val disposition: LocalWorkIntegrationDisposition,
    val reviewPendingCount: Int,
    val reviewRejectedCount: Int,
    val missingMachineEvidence: List<String>,
    val outstandingObligations: List<String>,
) {
    val readyForGoalAdmission: Boolean
        get() = disposition == LocalWorkIntegrationDisposition.READY_FOR_GOAL_ADMISSION
}

/**
 * Does not read files or execute commands itself. The caller must supply a current
 * LocalWorkDeliveryEvidence from LocalSessionEventLog and the current workspace hashes.
 * Any incomplete/rejected Team review or failed objective proof blocks admission.
 */
internal fun decideLocalWorkIntegration(
    evidence: LocalWorkCompletionEvidence,
): LocalWorkIntegrationDecision {
    val missingProof = evidence.deliveryEvidence?.missing.orEmpty()
    val decision = when {
        evidence.rejectedTeamResults > 0 -> LocalWorkIntegrationDisposition.REVIEW_REJECTED
        evidence.unreviewedTeamResults > 0 || evidence.awaitingReviewMembers > 0 ->
            LocalWorkIntegrationDisposition.REVIEW_REQUIRED
        missingProof.isNotEmpty() -> LocalWorkIntegrationDisposition.MACHINE_EVIDENCE_MISSING
        !evidence.readyForGoalCompletion -> LocalWorkIntegrationDisposition.OUTSTANDING_WORK
        else -> LocalWorkIntegrationDisposition.READY_FOR_GOAL_ADMISSION
    }
    return LocalWorkIntegrationDecision(
        disposition = decision,
        reviewPendingCount = evidence.unreviewedTeamResults + evidence.awaitingReviewMembers,
        reviewRejectedCount = evidence.rejectedTeamResults,
        missingMachineEvidence = missingProof,
        outstandingObligations = evidence.userReasons,
    )
}
