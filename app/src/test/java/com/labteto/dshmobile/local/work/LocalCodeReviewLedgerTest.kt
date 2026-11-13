package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCodeReviewLedgerTest {
    @Test fun recoverSameDiffAfterRestartWithoutAnotherReviewRequest() {
        val root = Files.createTempDirectory("code-review-recover-").toFile()
        val file = root.resolve("session.events.jsonl")
        val digest = codeReviewDigest("project", "origin/main", "1 file changed", "@@ -1 +1 @@")
        try {
            val firstLog = LocalSessionEventLog(file, Json)
            val ledger = LocalCodeReviewLedger(firstLog)
            ledger.request("review-1", "origin/main", digest)
            val initial = ledger.complete("review-1", "origin/main", digest, "检查完成，记录风险", "model-1")
            assertEquals(initial, ledger.latestForDigest("origin/main", digest))
            assertNull(ledger.latestForDigest("origin/main", "changed-digest"))
            assertTrue(ledger.accept("review-1", initial.evidenceSequence, digest))
            assertFalse(ledger.accept("review-1", initial.evidenceSequence, digest))
            firstLog.close()

            val recoveredLog = LocalSessionEventLog(file, Json)
            val recovered = requireNotNull(LocalCodeReviewLedger(recoveredLog)
                .latestForDigest("origin/main", digest))
            assertEquals(initial.id, recovered.id)
            assertEquals(initial.evidenceSequence, recovered.evidenceSequence)
            assertTrue(recovered.humanAccepted)
            assertNull(LocalCodeReviewLedger(recoveredLog).latestForDigest("origin/other", digest))
            recoveredLog.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun gitBaseRevisionChangeInvalidatesAnOtherwiseIdenticalPatch() {
        val previous = codeReviewDigest("repo", "origin/main", "1 file", "@@ +fixed",
            "head-a:remote-a:base-a")
        for (revision in listOf(
            "head-b:remote-a:base-a",
            "head-a:remote-b:base-a",
            "head-a:remote-a:base-b",
        )) {
            assertFalse(previous == codeReviewDigest("repo", "origin/main",
                "1 file", "@@ +fixed", revision))
        }
    }

    @Test fun newestMatchingEvidenceWinsAndStaleAcceptanceCannotAffectIt() {
        val root = Files.createTempDirectory("code-review-snapshots-").toFile()
        val log = LocalSessionEventLog(root.resolve("session.events.jsonl"), Json)
        try {
            val ledger = LocalCodeReviewLedger(log)
            val digest = codeReviewDigest("repo", "origin/main", "stat", "patch")
            val old = ledger.complete("review-old", "origin/main", digest, "第一轮", "model-a")
            assertTrue(ledger.accept(old.id, old.evidenceSequence, digest))
            val current = ledger.complete("review-new", "origin/main", digest, "第二轮", "model-b")
            val found = requireNotNull(ledger.latestForDigest("origin/main", digest))
            assertEquals(current.id, found.id)
            assertFalse(found.humanAccepted)
        } finally {
            log.close()
            root.deleteRecursively()
        }
    }
}
