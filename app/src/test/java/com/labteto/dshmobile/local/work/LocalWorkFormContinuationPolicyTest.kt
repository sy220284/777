package com.labteto.dshmobile.local.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkFormContinuationPolicyTest {
    @Test fun pendingFormStopsGoalAutoContinuationUntilUserExplicitlySubmits() {
        assertFalse(shouldAutoContinueWorkGoalAfterForm(
            LocalWorkGoalDisposition.NEEDS_EXECUTION, awaitingSubmission = true))
        assertFalse(shouldAutoContinueWorkGoalAfterForm(
            LocalWorkGoalDisposition.READY_FOR_SETTLEMENT, awaitingSubmission = true))
        assertTrue(shouldAutoContinueWorkGoalAfterForm(
            LocalWorkGoalDisposition.NEEDS_EXECUTION, awaitingSubmission = false))
        assertTrue(shouldAutoContinueWorkGoalAfterForm(
            LocalWorkGoalDisposition.READY_FOR_SETTLEMENT, awaitingSubmission = false))
        assertFalse(shouldAutoContinueWorkGoalAfterForm(
            LocalWorkGoalDisposition.NO_GOAL, awaitingSubmission = false))
    }
}
