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

class LocalRequirementEvidenceIndexTest {
    @get:Rule val temporary = TemporaryFolder()
    private val work = listOf(LocalTodoItem("提交报告", "pending"))
    private val digest = "a".repeat(64)

    private fun link(log: LocalSessionEventLog, path: String): LocalSessionEventLog.Event =
        log.append("work/requirement-evidence", buildJsonObject {
            put("goal_id", "goal-a")
            put("requirement_index", 0)
            put("requirement", "提交报告")
            put("artifact_path", path)
            put("origin_sequence", 0L)
            put("source_call_id", "call-1")
            put("sha256", digest)
            put("assertion", "user_selected_reference_only")
        })

    private fun review(log: LocalSessionEventLog, link: Long, positive: Boolean) =
        log.append("work/requirement-evidence-review", buildJsonObject {
            put("goal_id", "goal-a")
            put("requirement_index", 0)
            put("requirement", "提交报告")
            put("artifact_path", "report.md")
            put("link_sequence", link)
            put("sha256", digest)
            put("approved_by_user", positive)
            put("assertion", "manual_content_review_only")
        })

    @Test fun checkpointColdRestoreOnlyFoldsDurableDeltaAndDoesNotPromoteWorkStatus() {
        val file = File(temporary.root, "events.jsonl")
        val first = LocalSessionEventLog(file, Json)
        // Hundreds of unrelated turns remain in the log, never in the evidence index.
        repeat(640) { first.append("chat/turn", buildJsonObject { put("turn", it) }) }
        val source = link(first, "report.md")
        review(first, source.sequence, true)
        val initial = indexedRequirementEvidence(first, work, "goal-a")
        assertEquals(642, initial.replayedEvents)
        assertEquals(1, initial.links.size)
        assertTrue(initial.reviews.single().approvedByUser)
        assertEquals("pending", work.single().status)
        assertEquals(0, indexedRequirementEvidence(first, work, "goal-a").replayedEvents)
        first.close()

        val reopened = LocalSessionEventLog(file, Json)
        try {
            assertEquals(0, indexedRequirementEvidence(reopened, work, "goal-a").replayedEvents)
            review(reopened, source.sequence, false)
            val delta = indexedRequirementEvidence(reopened, work, "goal-a")
            assertEquals(1, delta.replayedEvents)
            assertFalse(delta.reviews.single().approvedByUser)
            assertTrue(indexedRequirementEvidence(reopened, work, "goal-b").links.isEmpty())
            assertTrue(indexedRequirementEvidence(reopened,
                listOf(LocalTodoItem("已变更要求", "pending")), "goal-a").links.isEmpty())
        } finally { reopened.close() }
    }

    @Test fun machineAssertionColdRestoresByExactLinkAndInvalidatesOnReplacement() {
        val file = File(temporary.root, "machine-check-events.jsonl")
        val log = LocalSessionEventLog(file, Json)
        fun machine(linkId: Long, matches: Boolean) = log.append(
            "work/requirement-evidence-text-check", buildJsonObject {
                put("goal_id", "goal-a")
                put("requirement_index", 0)
                put("artifact_path", "report.md")
                put("link_sequence", linkId)
                put("sha256", digest)
                put("condition_sha256", "b".repeat(64))
                put("contains_required_text", matches)
                put("assertion", "utf8_contains_only_not_work_completion")
            },
        )
        val first = link(log, "report.md")
        machine(first.sequence, true)
        assertTrue(indexedRequirementEvidence(log, work, "goal-a")
            .textChecks.single().containsRequiredText)
        assertEquals(0, indexedRequirementEvidence(log, work, "goal-a").replayedEvents)
        log.close()
        val restored = LocalSessionEventLog(file, Json)
        try {
            val fresh = indexedRequirementEvidence(restored, work, "goal-a")
            assertEquals(0, fresh.replayedEvents)
            assertTrue(fresh.textChecks.single().containsRequiredText)
            restored.append("work/requirement-evidence-text-check", buildJsonObject {
                put("goal_id", "goal-a")
                put("requirement_index", 0)
                put("artifact_path", "report.md")
                put("link_sequence", first.sequence)
                put("sha256", digest)
                put("condition_sha256", "b".repeat(64))
                put("contains_required_text", false)
            })
            val failed = indexedRequirementEvidence(restored, work, "goal-a")
            assertEquals(1, failed.replayedEvents)
            assertFalse(failed.textChecks.single().containsRequiredText)
            link(restored, "report.md")
            val replaced = indexedRequirementEvidence(restored, work, "goal-a")
            assertTrue(replaced.textChecks.isEmpty())
            assertEquals("pending", work.single().status)
        } finally { restored.close() }
    }

    @Test fun damagedDerivedCheckpointRebuildsOnlyFromAuthoritativeEvents() {
        val file = File(temporary.root, "corrupt-index.jsonl")
        val log = LocalSessionEventLog(file, Json)
        try {
            val selected = link(log, "report.md")
            review(log, selected.sequence, true)
            val before = indexedRequirementEvidence(log, work, "goal-a")
            assertTrue(before.reviews.single().approvedByUser)
            val cache = requireNotNull(log.readProjectionCheckpoint(
                "work.requirement-evidence", 2))
            // Corrupt the disposable payload while leaving its outer record structurally valid.
            log.writeProjectionCheckpoint("work.requirement-evidence", 2,
                cache.throughSequence, "{not a cursor", log.resetGeneration)
            val rebuilt = indexedRequirementEvidence(log, work, "goal-a")
            assertEquals(2, rebuilt.replayedEvents)
            assertEquals(selected.sequence, rebuilt.links.single().eventSequence)
            assertTrue(rebuilt.reviews.single().approvedByUser)
            assertEquals("pending", work.single().status)
            // Once rebuilt, the repaired projection need not scan the old history again.
            assertEquals(0, indexedRequirementEvidence(log, work, "goal-a").replayedEvents)
        } finally { log.close() }
    }

    @Test fun replacingAnExactReferenceRetiresTheEarlierManualDecision() {
        val log = LocalSessionEventLog(File(temporary.root, "replacement.jsonl"), Json)
        try {
            val first = link(log, "report.md")
            review(log, first.sequence, true)
            assertEquals(1, indexedRequirementEvidence(log, work, "goal-a").reviews.size)
            link(log, "report.md")
            val newer = indexedRequirementEvidence(log, work, "goal-a")
            assertEquals(1, newer.links.size)
            assertTrue(newer.reviews.isEmpty())
            assertEquals(1, newer.replayedEvents)
        } finally { log.close() }
    }
}
