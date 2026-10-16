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
