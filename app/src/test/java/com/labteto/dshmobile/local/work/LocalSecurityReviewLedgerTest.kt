package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSecurityReviewLedgerTest {
    @Test fun approvalIsBoundToAnAllowVerdictAndExactGitSnapshotAcrossRestart() {
        val root = Files.createTempDirectory("security-review-ledger-").toFile()
        val file = root.resolve("session.events.jsonl")
        val digest = codeReviewDigest("project", "worktree/HEAD", "1 file", "@@ patch", "head-a")
        try {
            val original = LocalSessionEventLog(file, Json)
            val ledger = LocalSecurityReviewLedger(original)
            ledger.request("security-1", "worktree/HEAD", digest)
            val review = ledger.complete("security-1", "worktree/HEAD", digest,
                "风险：未发现明确风险", "allow", "model-1")
            assertFalse(review.humanApproved)
            assertFalse(ledger.approve(review.id, review.evidenceSequence, "other-digest"))
            assertFalse(ledger.approve(review.id, review.evidenceSequence + 1, digest))
            assertTrue(ledger.approve(review.id, review.evidenceSequence, digest))
            assertFalse(ledger.approve(review.id, review.evidenceSequence, digest))
            original.close()

            val recovered = LocalSessionEventLog(file, Json)
            val afterRestart = requireNotNull(LocalSecurityReviewLedger(recovered)
                .latestForDigest("worktree/HEAD", digest))
            assertTrue(afterRestart.humanApproved)
            assertEquals(review.evidenceSequence, afterRestart.evidenceSequence)
            assertNull(LocalSecurityReviewLedger(recovered)
                .latestForDigest("worktree/HEAD", "changed"))
            assertNull(LocalSecurityReviewLedger(recovered)
                .latestForDigest("refs/remotes/origin/main", digest))
            recovered.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun structuredSecurityDecisionNeverAllowsReportedRisks() {
        val allowed = parseIndependentSecurityReviewDecision(
            """{"verdict":"allow","reason":"代码没有明确高危风险","findings":"未发现明确风险"}""",
        )
        assertEquals("allow", allowed.verdict)
        val blocked = parseIndependentSecurityReviewDecision(
            """{"verdict":"block","reason":"存在越权操作","findings":"高危：授权检查缺失"}""",
        )
        assertEquals("block", blocked.verdict)
        for (invalid in listOf(
            """{"verdict":"allow","reason":"安全","findings":"高危：越权操作"}""",
            """{"verdict":"allow","reason":"","findings":"未发现明确风险"}""",
            """{"verdict":"allow","reason":"安全","findings":"未发现明确风险","ignored":true}""",
        )) {
            var refused = false
            try { parseIndependentSecurityReviewDecision(invalid) }
            catch (_: IllegalArgumentException) { refused = true }
            assertTrue("矛盾或不完整的安全裁决必须拒绝", refused)
        }
    }

    @Test fun riskVerdictCannotReceiveHumanApprovalAndNewReviewCannotInheritOldApproval() {
        val root = Files.createTempDirectory("security-review-block-").toFile()
        val log = LocalSessionEventLog(root.resolve("session.events.jsonl"), Json)
        try {
            val ledger = LocalSecurityReviewLedger(log)
            val digest = codeReviewDigest("repo", "origin/main", "stat", "patch", "head-1")
            val allowed = ledger.complete("safe", "origin/main", digest, "安全报告", "allow", "model-a")
            assertTrue(ledger.approve(allowed.id, allowed.evidenceSequence, digest))
            val blocked = ledger.complete("blocked", "origin/main", digest, "发现越权调用", "block", "model-b")
            assertFalse(ledger.approve(blocked.id, blocked.evidenceSequence, digest))
            val latest = requireNotNull(ledger.latestForDigest("origin/main", digest))
            assertEquals("block", latest.verdict)
            assertFalse(latest.humanApproved)
            assertFalse(ledger.read(blocked.id)!!.humanApproved)
        } finally {
            log.close()
            root.deleteRecursively()
        }
    }
}
