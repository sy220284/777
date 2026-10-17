package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.uicontract.UiWorkMachineVerification
import com.labteto.dshmobile.uicontract.UiWorkMachineVerificationStatus

/**
 * The Goal's original typed artifact paths and test commands are the only admitted
 * machine predicates. Human reviews and model-generated success prose cannot pass them.
 */
internal fun projectLocalWorkMachineVerification(
    goal: LocalGoal, delivery: LocalWorkDeliveryEvidence, sourceSequence: Long,
): UiWorkMachineVerification {
    require(sourceSequence >= -1L)
    val hasAssertions = goal.artifactPaths.isNotEmpty() || goal.verificationCommands.isNotEmpty()
    val missing = delivery.missing
    val state = when {
        !hasAssertions -> UiWorkMachineVerificationStatus.NO_MACHINE_CONDITIONS
        missing.isNotEmpty() -> UiWorkMachineVerificationStatus.UNSATISFIED
        else -> UiWorkMachineVerificationStatus.VERIFIED_CONDITIONS
    }
    return UiWorkMachineVerification(
        goal.id, goal.revision, sourceSequence, state,
        goal.artifactPaths.size, goal.verificationCommands.size,
        missing.take(16).map { it.take(512) },
        (missing.size - 16).coerceAtLeast(0),
    )
}
