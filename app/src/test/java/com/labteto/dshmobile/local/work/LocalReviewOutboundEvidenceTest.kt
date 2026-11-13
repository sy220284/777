package com.labteto.dshmobile.local.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReviewOutboundEvidenceTest {
    @Test fun modelPayloadHidesCommonCredentialsWithoutChangingReviewSnapshot() {
        val raw = """
            diff --git a/config.txt b/config.txt
            +api_key=sk-abcdefghijklmnopqrstuvwxyz
            +Authorization: Bearer sample.long.token
            +github_pat_abcdEFGH1234567890
            +val harmless = "review me"
        """.trimIndent()
        val original = codeReviewDigest("repo", "worktree/HEAD", "4 lines", raw)
        val outbound = prepareOutboundReviewPatch(raw)
        assertFalse(outbound.contains("sk-abcdefghijklmnopqrstuvwxyz"))
        assertFalse(outbound.contains("sample.long.token"))
        assertFalse(outbound.contains("github_pat_abcdEFGH1234567890"))
        assertTrue(outbound.contains("<redacted>"))
        assertTrue(outbound.contains("harmless"))
        assertFalse(original == codeReviewDigest("repo", "worktree/HEAD", "4 lines", outbound))
    }

    @Test fun privateKeyBlocksFailBeforeAnyModelRequest() {
        var denied = false
        try {
            prepareOutboundReviewPatch("+-----BEGIN OPENSSH PRIVATE KEY-----\n+secret")
        } catch (error: IllegalArgumentException) {
            denied = true
            assertTrue(error.message.orEmpty().contains("私钥"))
        }
        assertTrue(denied)
    }
}
