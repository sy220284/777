package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.model.LocalModelCompletionReason
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalIndependentReviewCoordinatorReceiptTest {
    @get:Rule val folder = TemporaryFolder()

    private fun reply(content: String?, reason: LocalModelCompletionReason =
        LocalModelCompletionReason.COMPLETED, toolCalls: List<LocalToolCall> = emptyList()) =
        LocalModelReply(
            message = buildJsonObject {}, content = content, reasoning = null,
            toolCalls = toolCalls, completionReason = reason,
        )

    private fun log() = LocalSessionEventLog(File(folder.newFolder(), "events.jsonl"), Json)

    @Test fun codeReviewStoresOnlyCompleteModelReceiptAndPreservesExactRawDigest() = runTest {
        val events = log()
        try {
            val rawPatch = "+password=secret-value\n+val safe = 1"
            var outbound = ""
            val receipt = runIndependentCodeReview(
                log = events, repository = "repo", ref = "worktree/HEAD",
                statistics = "1 file", patch = rawPatch, truncated = false,
                profileId = "reviewer",
            ) {
                outbound = it
                reply("发现一个问题")
            }
            assertFalse(outbound.contains("secret-value"))
            assertEquals(codeReviewDigest("repo", "worktree/HEAD", "1 file", rawPatch), receipt.digest)
            assertEquals("发现一个问题", receipt.report)
            assertEquals(receipt.evidenceSequence, LocalCodeReviewLedger(events).read(receipt.id)?.evidenceSequence)
        } finally { events.close() }
    }

    @Test fun incompleteEmptyOrToolCallingReviewsCannotCreateReviewResults() = runTest {
        val events = log()
        try {
            val toolCall = LocalToolCall("call", "bash", JsonObject(emptyMap()), "{}")
            for (response in listOf(
                reply("unfinished", LocalModelCompletionReason.OUTPUT_LIMIT),
                reply("refused", LocalModelCompletionReason.REFUSED),
                reply("no tool should run", toolCalls = listOf(toolCall)),
                reply("  "),
                reply("X".repeat(16_385)),
            )) {
                assertTrue(runCatching {
                    runIndependentCodeReview(events, "repo", "worktree/HEAD",
                        "stat", "+allowed=true", false, profileId = "reviewer") { response }
                }.isFailure)
            }
            assertTrue(events.snapshot().none { it.type == "work/code-review-result" })
        } finally { events.close() }
    }

    @Test fun securityReviewBlocksContradictionsAndNeverConvertsModelOpinionToAuthorization() = runTest {
        val events = log()
        try {
            val accepted = runIndependentSecurityReview(
                events, "repo", "worktree/HEAD", "stat", "+safe=true",
                truncated = false, profileId = "security-profile",
            ) { reply("""{"verdict":"allow","reason":"未发现问题","findings":"未发现明确风险"}""") }
            assertEquals("allow", accepted.verdict)
            assertFalse(accepted.humanApproved)
            assertFalse(LocalSecurityReviewLedger(events).read(accepted.id)!!.humanApproved)
            assertTrue(runCatching {
                runIndependentSecurityReview(events, "repo", "worktree/HEAD", "stat",
                    "+safe=false", false, profileId = "security-profile") {
                    reply("""{"verdict":"allow","reason":"安全","findings":"高危：越权"}""")
                }
            }.isFailure)
            assertTrue(runCatching {
                runIndependentSecurityReview(events, "repo", "worktree/HEAD", "stat",
                    "+safe=false", false, profileId = "security-profile") {
                    reply("", LocalModelCompletionReason.INTERRUPTED)
                }
            }.isFailure)
            assertEquals(1, events.snapshot().count { it.type == "work/security-review-result" })
        } finally { events.close() }
    }

    @Test fun secretKeyPreflightRejectsBeforeAnyBillableModelRequest() = runTest {
        val events = log()
        try {
            var invoked = false
            assertTrue(runCatching {
                runIndependentSecurityReview(events, "repo", "worktree/HEAD", "stat",
                    "+-----BEGIN OPENSSH PRIVATE KEY-----\n+material", false,
                    profileId = "reviewer") {
                    invoked = true
                    reply("{}")
                }
            }.isFailure)
            assertFalse(invoked)
            assertTrue(events.snapshot().none { it.type.endsWith("-review-start") })
        } finally { events.close() }
    }
}
