package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalIndependentCodeReviewTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun userAcceptanceBindsExactDiffAndReviewerReceipt() {
        val file = File(folder.root, "review.events.jsonl")
        val original = LocalSessionEventLog(file, Json)
        val ledger = LocalCodeReviewLedger(original)
        val digest = codeReviewDigest(".", "origin/main", "one file", "+fix")
        ledger.request("review-1", "origin/main", digest)
        val result = ledger.complete("review-1", "origin/main", digest, "发现一个风险", "profile")
        assertFalse(ledger.accept("review-1", result.evidenceSequence, "changed"))
        assertFalse(ledger.accept("review-1", result.evidenceSequence + 1L, digest))
        assertFalse(ledger.accept("another-review", result.evidenceSequence, digest))
        assertTrue(ledger.accept("review-1", result.evidenceSequence, digest))
        assertFalse(ledger.accept("review-1", result.evidenceSequence, digest))
        original.close()

        val reopened = LocalSessionEventLog(file, Json)
        val saved = requireNotNull(LocalCodeReviewLedger(reopened).read("review-1"))
        assertTrue(saved.humanAccepted)
        assertEquals("发现一个风险", saved.report)
        reopened.close()
    }

    @Test fun hashCoversSourceReferenceAndPatchContents() {
        val baseline = codeReviewDigest(".", "origin/main", "stat", "patch")
        assertFalse(baseline == codeReviewDigest("other", "origin/main", "stat", "patch"))
        assertFalse(baseline == codeReviewDigest(".", "upstream/main", "stat", "patch"))
        assertFalse(baseline == codeReviewDigest(".", "origin/main", "other", "patch"))
        assertFalse(baseline == codeReviewDigest(".", "origin/main", "stat", "modified"))
    }
}
