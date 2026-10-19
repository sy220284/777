package com.labteto.dshmobile.local.work

/**
 * A published input form is an explicit user-turn boundary. The Work goal remains pending,
 * but the automatic driver must not create a new assistant response before the user submits.
 */
internal fun shouldAutoContinueWorkGoalAfterForm(
    disposition: LocalWorkGoalDisposition?,
    awaitingSubmission: Boolean,
): Boolean = !awaitingSubmission &&
    disposition in setOf(
        LocalWorkGoalDisposition.NEEDS_EXECUTION,
        LocalWorkGoalDisposition.READY_FOR_SETTLEMENT,
    )
