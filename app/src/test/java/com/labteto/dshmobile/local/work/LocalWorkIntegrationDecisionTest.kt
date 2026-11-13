package com.labteto.dshmobile.local.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkIntegrationDecisionTest {
    private fun teamTask(review: String) = LocalAgentTeamTaskUiState(
        id = "task-verified", subject = "交付物核验", description = "",
        status = "completed", revision = 3,
        completionResultId = "worker-checkpoint",
        completionEvidenceSequence = 71L,
        independentReviewStatus = review,
    )

    private fun work(review: String) = LocalWorkState(
        goal = LocalGoal("测试并交付", id = "goal-integrate"),
        team = LocalAgentTeamUiState(tasks = listOf(teamTask(review))),
    )

    private fun machineEvidence() = LocalWorkDeliveryEvidence(
        needsArtifact = true, needsTest = true,
        artifactCallId = "persisted-file-sha", testCallId = "real-test-exit-zero",
    )

    @Test fun reviewApprovalCannotLaunderMissingOrFailedMachineEvidence() {
        val passed = localWorkCompletionEvidence(work("reviewer_approved"), machineEvidence())
        assertEquals(LocalWorkIntegrationDisposition.READY_FOR_GOAL_ADMISSION,
            decideLocalWorkIntegration(passed).disposition)

        val missingTest = localWorkCompletionEvidence(work("reviewer_approved"),
            machineEvidence().copy(testCallId = null))
        assertEquals(LocalWorkIntegrationDisposition.MACHINE_EVIDENCE_MISSING,
            decideLocalWorkIntegration(missingTest).disposition)
        assertFalse(decideLocalWorkIntegration(missingTest).readyForGoalAdmission)

        val changedArtifact = localWorkCompletionEvidence(work("reviewer_approved"),
            machineEvidence().copy(artifactCallId = null))
        assertEquals(LocalWorkIntegrationDisposition.MACHINE_EVIDENCE_MISSING,
            decideLocalWorkIntegration(changedArtifact).disposition)
    }

    @Test fun reviewRejectionAndMissingReviewBlockEvenWithGoodFileAndTestResults() {
        val rejected = decideLocalWorkIntegration(
            localWorkCompletionEvidence(work("reviewer_rejected"), machineEvidence()))
        assertEquals(LocalWorkIntegrationDisposition.REVIEW_REJECTED, rejected.disposition)
        assertEquals(1, rejected.reviewRejectedCount)
        assertFalse(rejected.readyForGoalAdmission)

        val missing = decideLocalWorkIntegration(
            localWorkCompletionEvidence(work("review_invalid"), machineEvidence()))
        assertEquals(LocalWorkIntegrationDisposition.REVIEW_REQUIRED, missing.disposition)
        assertEquals(1, missing.reviewPendingCount)
        assertFalse(missing.readyForGoalAdmission)
    }

    @Test fun oldCompletedTaskCannotBypassOutstandingWorkOrUserApproval() {
        val legacy = work("legacy_unknown").copy(team = LocalAgentTeamUiState(
            tasks = listOf(teamTask("legacy_unknown").copy(
                completionResultId = null, completionEvidenceSequence = null,
            )),
        ))
        assertTrue(decideLocalWorkIntegration(localWorkCompletionEvidence(legacy, machineEvidence()))
            .readyForGoalAdmission)
        val withPendingTodo = legacy.copy(todos = listOf(LocalTodoItem("待整合", "pending")))
        assertEquals(LocalWorkIntegrationDisposition.OUTSTANDING_WORK,
            decideLocalWorkIntegration(localWorkCompletionEvidence(withPendingTodo, machineEvidence()))
                .disposition)
    }

    @Test fun goalDiagnosticUsesExactlyTheSameDecisionAsGoalDriver() {
        val evidence = localWorkCompletionEvidence(work("reviewer_rejected"), machineEvidence())
        val decision = decideLocalWorkIntegration(evidence)
        assertEquals("${decision.disposition.name.lowercase()}",
            evidence.toDiagnosticJson()["integration_disposition"].toString().trim('"'))
    }
}
