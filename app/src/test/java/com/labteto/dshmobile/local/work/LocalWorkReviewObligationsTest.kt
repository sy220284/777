package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkReviewObligationsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun <T> withGoal(requirements: List<String>, block: (
        LocalSessionEventLog, LocalGoal
    ) -> T): T {
        val log = LocalSessionEventLog(File(folder.newFolder(), "events.jsonl"), Json)
        try {
            val initial = LocalGoal("真实交付", id = "goal-review", revision = 1L,
                reviewRequirements = requirements)
            val created = log.append("goal/state", workGoalEvent(initial))
            return block(log, initial.copy(startedAtSequence = created.sequence))
        } finally {
            log.close()
        }
    }

    @Test fun ordinaryGoalsDoNotRequireIndependentReview() = withGoal(emptyList()) { log, goal ->
        assertTrue(localWorkDeliveryEvidence(log, goal).outstandingReviews.isEmpty())
    }

    @Test fun explicitlyRequiredCodeAndSecurityReviewsHaveSeparateRealApprovals() =
        withGoal(listOf("code", "security")) { log, goal ->
            val original = localWorkDeliveryEvidence(log, goal)
            assertEquals(2, original.outstandingReviews.size)
            val code = LocalCodeReviewLedger(log)
            val codeBinding = requireNotNull(currentReviewGoalBinding(log, "code"))
            assertEquals(goal.id, codeBinding.goalId)
            assertEquals(goal.startedAtSequence, codeBinding.startSequence)
            code.request("code-1", "worktree/HEAD", "digest-code", codeBinding)
            val codeReceipt = code.complete("code-1", "worktree/HEAD", "digest-code",
                "检查结果", "review-profile")
            assertTrue(code.accept("code-1", codeReceipt.evidenceSequence, "digest-code"))
            assertEquals(1, localWorkDeliveryEvidence(log, goal).outstandingReviews.size)

            val security = LocalSecurityReviewLedger(log)
            security.request("security-1", "worktree/HEAD", "digest-security",
                currentReviewGoalBinding(log, "security"))
            val securityReceipt = security.complete("security-1", "worktree/HEAD", "digest-security",
                "未发现明确风险", "allow", "security-profile")
            assertFalse(localWorkDeliveryEvidence(log, goal).outstandingReviews.isEmpty())
            assertTrue(security.approve("security-1", securityReceipt.evidenceSequence, "digest-security"))
            val complete = localWorkDeliveryEvidence(log, goal)
            assertTrue(complete.outstandingReviews.isEmpty())
            val approved = LocalWorkState(goal = goal)
            assertTrue(resolveWorkGoalTransition(goal, goal.copy(status = "completed", revision = 2),
                approved, complete).accepted)
            assertTrue(localWorkDeliveryEvidence(log, goal.copy(status = "completed", revision = 2))
                .outstandingReviews.isEmpty())
        }

    @Test fun blockedReviewAndNewReviewAttemptCannotInheritOldApproval() =
        withGoal(listOf("security")) { log, goal ->
            val security = LocalSecurityReviewLedger(log)
            val binding = currentReviewGoalBinding(log, "security")
            security.request("blocked", "worktree/HEAD", "blocked-digest", binding)
            val risk = security.complete("blocked", "worktree/HEAD", "blocked-digest",
                "发现越权", "block", "profile")
            assertFalse(security.approve("blocked", risk.evidenceSequence, "blocked-digest"))
            assertTrue(localWorkDeliveryEvidence(log, goal).missing.any { it.contains("风险") })

            security.request("clean", "worktree/HEAD", "clean-digest", binding)
            val safe = security.complete("clean", "worktree/HEAD", "clean-digest",
                "检查完成", "allow", "profile")
            assertTrue(security.approve("clean", safe.evidenceSequence, "clean-digest"))
            assertTrue(localWorkDeliveryEvidence(log, goal).outstandingReviews.isEmpty())

            security.request("new", "worktree/HEAD", "changed-digest", binding)
            assertTrue(localWorkDeliveryEvidence(log, goal).outstandingReviews.isNotEmpty())
        }

    @Test fun sessionOnlyProgressAndUnrelatedGoalDoNotInvalidateReview() =
        withGoal(listOf("code")) { log, goal ->
            val ledger = LocalCodeReviewLedger(log)
            ledger.request("code-1", "worktree/HEAD", "digest",
                currentReviewGoalBinding(log, "code"))
            val review = ledger.complete("code-1", "worktree/HEAD", "digest",
                "检查完成", "reviewer")
            assertTrue(ledger.accept(review.id, review.evidenceSequence, review.digest))
            log.append("tool/call", buildJsonObject {
                put("id", "todo-1"); put("goal_id", goal.id); put("name", "todo_write")
            })
            log.append("tool/result", buildJsonObject {
                put("id", "todo-1"); put("name", "todo_write")
                put("is_error", false); put("side_effect", "possible")
            })
            log.append("tool/call", buildJsonObject {
                put("id", "other-goal"); put("goal_id", "unrelated"); put("name", "edit")
            })
            log.append("tool/result", buildJsonObject {
                put("id", "other-goal"); put("name", "edit")
                put("is_error", false); put("side_effect", "possible")
            })
            assertTrue(localWorkDeliveryEvidence(log, goal).outstandingReviews.isEmpty())
        }

    @Test fun editStartedBeforeApprovalButSettledAfterApprovalInvalidatesIt() =
        withGoal(listOf("code")) { log, goal ->
            val ledger = LocalCodeReviewLedger(log)
            log.append("tool/call", buildJsonObject {
                put("id", "in-flight-edit"); put("name", "edit"); put("goal_id", goal.id)
            })
            ledger.request("code-1", "worktree/HEAD", "digest",
                currentReviewGoalBinding(log, "code"))
            val review = ledger.complete("code-1", "worktree/HEAD", "digest",
                "检查完成", "reviewer")
            assertTrue(ledger.accept(review.id, review.evidenceSequence, review.digest))
            log.append("tool/result", buildJsonObject {
                put("id", "in-flight-edit"); put("name", "edit")
                put("is_error", false); put("side_effect", "possible")
            })
            assertTrue(localWorkDeliveryEvidence(log, goal).outstandingReviews.any {
                it.contains("新的修改")
            })
        }

    @Test fun subsequentGoalOwnedMutationInvalidatesAcknowledgedSnapshot() =
        withGoal(listOf("code")) { log, goal ->
            val code = LocalCodeReviewLedger(log)
            code.request("code-1", "worktree/HEAD", "same-digest",
                currentReviewGoalBinding(log, "code"))
            val result = code.complete("code-1", "worktree/HEAD", "same-digest",
                "检查完成", "profile")
            assertTrue(code.accept("code-1", result.evidenceSequence, "same-digest"))
            assertTrue(localWorkDeliveryEvidence(log, goal).outstandingReviews.isEmpty())
            log.append("tool/call", buildJsonObject {
                put("id", "edit-1"); put("name", "edit"); put("goal_id", goal.id)
            })
            log.append("tool/result", buildJsonObject {
                put("id", "edit-1"); put("name", "edit")
                put("side_effect", "possible"); put("is_error", false)
            })
            assertTrue(localWorkDeliveryEvidence(log, goal).outstandingReviews.any {
                it.contains("新的修改")
            })
        }
}
