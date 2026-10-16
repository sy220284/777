package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRequirementEvidenceProjectionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun selectedVersionCannotSilentlyBindAFileChangedAfterSelection() {
        val root = temp.newFolder("selected-workspace")
        val file = File(root, "report.md").apply { writeText("selected bytes") }
        val digest = localWorkFileSha256(root, "report.md")!!
        val source = LocalSessionEventLog.Event(
            sequence = 0L, type = "tool/result", createdAt = 1L,
            data = buildJsonObject {
                put("id", "call-1")
                put("name", "write")
                put("artifact_path", "report.md")
                put("artifact_sha256", digest)
            },
        )
        val selected = LocalArtifactUiItem(
            reference = "report.md", category = "file", sourceCallId = "call-1",
            asOfSequence = 0L, currentlyAvailable = true,
            versionAtCreation = digest, versionNow = digest,
        )
        assertEquals(digest, selectedRequirementEvidenceVersion(root, source, selected))
        file.writeText("changed after user selected")
        assertEquals(null, selectedRequirementEvidenceVersion(root, source, selected))
        val currentDigest = localWorkFileSha256(root, "report.md")!!
        val refreshed = selected.copy(versionNow = currentDigest)
        assertEquals(currentDigest, selectedRequirementEvidenceVersion(root, source, refreshed))
        assertEquals(null, selectedRequirementEvidenceVersion(root, source,
            refreshed.copy(versionAtCreation = "a".repeat(64))))
        assertEquals(null, selectedRequirementEvidenceVersion(root, source,
            refreshed.copy(versionNow = null)))
        assertEquals(null, selectedRequirementEvidenceVersion(root, source.copy(sequence = 1L), refreshed))
        file.delete()
        assertEquals(null, selectedRequirementEvidenceVersion(root, source, refreshed))
    }

    @Test fun evidencePreparedAgainstOlderBytesIsRejectedBeforeOwnerCommit() {
        val root = temp.newFolder("evidence-final-check")
        val file = File(root, "report.md").apply { writeText("version A") }
        val selectedVersion = localWorkFileSha256(root, "report.md")!!
        assertTrue(preparedRequirementEvidenceVersionMatches(root, "report.md", selectedVersion))

        // Simulate an external tool updating the file while the user is choosing a requirement.
        file.writeText("version B")
        assertFalse(preparedRequirementEvidenceVersionMatches(root, "report.md", selectedVersion))
        assertFalse(preparedRequirementEvidenceVersionMatches(root, "../escape", selectedVersion))
        assertFalse(preparedRequirementEvidenceVersionMatches(root, "report.md", "not-a-hash"))
        assertTrue(preparedRequirementEvidenceVersionMatches(root, "report.md",
            localWorkFileSha256(root, "report.md")!!))
    }

    @Test fun evidenceReferenceCanNeverResolveThroughAnAlias() {
        val root = temp.newFolder("evidence-link-alias")
        val actual = File(root, "source.txt").apply { writeText("trusted") }
        val alias = File(root, "reference.txt")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), actual.toPath())
        val digest = localWorkFileSha256(root, "source.txt")!!
        assertFalse(preparedRequirementEvidenceVersionMatches(root, "reference.txt", digest))
        assertTrue(preparedRequirementEvidenceVersionMatches(root, "source.txt", digest))
    }

    @Test fun archivedEvidenceReadsCurrentFileWithoutRecentArtifactProjection() {
        val root = temp.newFolder("workspace")
        val file = File(root, "reports/one.md").also { it.parentFile.mkdirs() }
        file.writeText("first content")
        val digest = localWorkFileSha256(root, "reports/one.md")!!
        val task = listOf(LocalTodoItem("交付报告", "completed"))
        val link = projectRequirementEvidenceLinks(listOf(link(1200, 0, "交付报告", digest)), task).single()
        assertEquals(digest, checkRequirementEvidenceVersions(root, listOf(link)).single().versionNow)
        file.writeText("later revision")
        val changed = checkRequirementEvidenceVersions(root, listOf(link)).single()
        assertNotEquals(digest, changed.versionNow)
        assertEquals(digest, changed.versionAtLink)
        assertEquals(link.artifactSequence, changed.artifactSequence)
        file.delete()
        assertEquals(null, checkRequirementEvidenceVersions(root, listOf(link)).single().versionNow)
    }

    private fun link(seq: Long, index: Int, title: String, sha: String) = LocalSessionEventLog.Event(
        sequence = seq, type = "work/requirement-evidence", createdAt = seq,
        data = buildJsonObject {
            put("requirement_index", index)
            put("requirement", title)
            put("artifact_path", "reports/one.md")
            put("origin_sequence", 10L)
            put("source_call_id", "call-1")
            put("sha256", sha)
        },
    )

    @Test fun firstEventSequenceIsAValidEvidenceOriginButNegativeSequenceIsNot() {
        val tasks = listOf(LocalTodoItem("交付", "completed"))
        fun atOrigin(origin: Long) = link(1, 0, "交付", "a".repeat(64)).let { event ->
            event.copy(data = buildJsonObject {
                event.data.forEach { (key, value) -> put(key, value) }
                put("origin_sequence", origin)
            })
        }
        assertEquals(0L, projectRequirementEvidenceLinks(listOf(atOrigin(0)), tasks).single().artifactSequence)
        assertTrue(projectRequirementEvidenceLinks(listOf(atOrigin(-1)), tasks).isEmpty())
    }

    @Test fun requirementLinkMustMatchSameTaskPositionAndText() {
        val tasks = listOf(LocalTodoItem("撰写报告", "completed"), LocalTodoItem("运行测试", "pending"))
        val sha = "a".repeat(64)
        val links = projectRequirementEvidenceLinks(
            listOf(link(20, 0, "撰写报告", sha), link(21, 1, "过期任务内容", sha)), tasks,
        )
        assertEquals(1, links.size)
        assertEquals("撰写报告", links.single().requirement)
        assertEquals(20L, links.single().eventSequence)
        assertTrue(projectRequirementEvidenceLinks(listOf(link(20, 0, "撰写报告", sha)),
            listOf(LocalTodoItem("改成另一条要求", "completed"))).isEmpty())
    }

    @Test fun unreadableFakeShaNeverBecomesEvidence() {
        val tasks = listOf(LocalTodoItem("交付", "completed"))
        assertTrue(projectRequirementEvidenceLinks(listOf(link(1, 0, "交付", "madeup")), tasks).isEmpty())
    }

    @Test fun userReviewColdRestoresWithoutPromotingRequirementToCompleted() {
        val root = temp.newFolder("manual-work-review")
        val file = File(root, "session.events.jsonl")
        val json = kotlinx.serialization.json.Json
        val log = LocalSessionEventLog(file, json)
        val digest = "a".repeat(64)
        val link = log.append("work/requirement-evidence", buildJsonObject {
            put("goal_id", "goal-a"); put("requirement_index", 0)
            put("requirement", "报告"); put("artifact_path", "report.md")
            put("origin_sequence", 0L); put("source_call_id", "call-a")
            put("sha256", digest); put("assertion", "user_selected_reference_only")
        })
        log.append("work/requirement-evidence-review", buildJsonObject {
            put("goal_id", "goal-a"); put("requirement_index", 0)
            put("requirement", "报告"); put("artifact_path", "report.md")
            put("link_sequence", link.sequence); put("sha256", digest)
            put("approved_by_user", true); put("assertion", "manual_content_review_only")
        })
        log.close()
        val restored = LocalSessionEventLog(file, json)
        try {
            val tasks = listOf(LocalTodoItem("报告", "pending"))
            val links = restored.withEvents { projectRequirementEvidenceLinks(it, tasks, "goal-a") }
            val reviews = restored.withEvents { projectRequirementEvidenceReviews(it, links, "goal-a") }
            assertEquals("pending", tasks.single().status)
            assertEquals(link.sequence, reviews.single().linkSequence)
            assertTrue(reviews.single().approvedByUser)
            assertTrue(restored.withEvents { projectRequirementEvidenceReviews(it, links, "goal-b") }.isEmpty())
        } finally { restored.close() }
    }

    @Test fun literalContentCheckUsesVerifiedBytesAndCannotClaimWorkCompletion() {
        val root = temp.newFolder("literal-check")
        val actual = File(root, "output.txt").apply { writeText("written report content") }
        val sha = localWorkFileSha256(root, "output.txt")!!
        assertEquals(true, inspectLocalWorkEvidenceText(root, "output.txt", sha, "report"))
        assertEquals(false, inspectLocalWorkEvidenceText(root, "output.txt", sha, "signed"))
        assertEquals(null, inspectLocalWorkEvidenceText(root, "output.txt", sha, ""))
        actual.writeText("changed bytes")
        assertEquals(null, inspectLocalWorkEvidenceText(root, "output.txt", sha, "report"))
        assertEquals(null, inspectLocalWorkEvidenceText(root, "../output.txt", sha, "report"))
    }

    @Test fun contentCheckEventsAreExactLinkGoalScopedAndCanBeColdRebuilt() {
        val sha = "a".repeat(64)
        val evidence = LocalSessionEventLog.Event(10L, "work/requirement-evidence", 10L, buildJsonObject {
            put("goal_id", "goal"); put("requirement_index", 0)
            put("requirement", "交付报告"); put("artifact_path", "output.txt")
            put("origin_sequence", 1L); put("source_call_id", "call"); put("sha256", sha)
        })
        val todos = listOf(LocalTodoItem("交付报告", "pending"))
        val links = projectRequirementEvidenceLinks(sequenceOf(evidence), todos, "goal")
        fun check(seq: Long, goal: String, matched: Boolean) =
            LocalSessionEventLog.Event(seq, "work/requirement-evidence-text-check", seq, buildJsonObject {
                put("goal_id", goal); put("requirement_index", 0)
                put("artifact_path", "output.txt"); put("link_sequence", 10L)
                put("sha256", sha); put("condition_sha256", "b".repeat(64))
                put("contains_required_text", matched)
            })
        val checks = projectRequirementEvidenceTextChecks(sequenceOf(
            check(11L, "goal", true), check(12L, "other", true), check(13L, "goal", false)
        ), links, "goal")
        assertEquals(1, checks.size)
        assertFalse(checks.single().containsRequiredText)
        assertEquals(13L, checks.single().checkSequence)
        assertEquals("pending", todos.single().status)
        assertTrue(projectRequirementEvidenceTextChecks(sequenceOf(check(14L, "goal", true)),
            emptyList(), "goal").isEmpty())
    }

    @Test fun manualReviewRequiresExactGoalLinkRevisionAndDigest() { {
        val digest = "a".repeat(64)
        val tasks = listOf(LocalTodoItem("交付报告", "completed"))
        fun goalLink(sequence: Long, path: String = "reports/one.md") = link(
            sequence, 0, "交付报告", digest,
        ).copy(data = buildJsonObject {
            put("requirement_index", 0)
            put("requirement", "交付报告")
            put("artifact_path", path)
            put("origin_sequence", 10L)
            put("source_call_id", "call-1")
            put("sha256", digest)
            put("goal_id", "goal-a")
        })
        fun review(sequence: Long, linkSequence: Long = 20L, goal: String = "goal-a",
            sha: String = digest, approved: Boolean = true,
        ) = LocalSessionEventLog.Event(sequence, "work/requirement-evidence-review", sequence,
            buildJsonObject {
                put("goal_id", goal)
                put("requirement_index", 0)
                put("requirement", "交付报告")
                put("artifact_path", "reports/one.md")
                put("link_sequence", linkSequence)
                put("sha256", sha)
                put("approved_by_user", approved)
            })
        val selected = projectRequirementEvidenceLinks(sequenceOf(goalLink(20L)), tasks, "goal-a")
        assertEquals(1, selected.size)
        val accepted = projectRequirementEvidenceReviews(
            sequenceOf(review(21L), review(22L, goal = "other"), review(23L, sha = "b".repeat(64))),
            selected, "goal-a")
        assertTrue(accepted.single().approvedByUser)
        assertEquals(21L, accepted.single().reviewSequence)
        val withdrawn = projectRequirementEvidenceReviews(
            sequenceOf(review(21L), review(24L, approved = false)), selected, "goal-a")
        assertFalse(withdrawn.single().approvedByUser)
        assertEquals(24L, withdrawn.single().reviewSequence)
        assertTrue(projectRequirementEvidenceReviews(sequenceOf(review(25L)), selected, "goal-b").isEmpty())
        // A new reference supersedes its old event ID even if the file path and task text repeat.
        val newer = projectRequirementEvidenceLinks(
            sequenceOf(goalLink(20L), goalLink(26L)), tasks, "goal-a")
        assertEquals(26L, newer.single().eventSequence)
        assertTrue(projectRequirementEvidenceReviews(sequenceOf(review(27L)), newer, "goal-a").isEmpty())
        assertTrue(projectRequirementEvidenceReviews(
            sequenceOf(review(20L)), selected, "goal-a").isEmpty())
    }

    @Test fun scopedLinksCannotMoveToAnotherGoalAndLegacyReferencesRemainReadable() {
        val todos = listOf(LocalTodoItem("交付", "completed"))
        val legacy = link(20, 0, "交付", "a".repeat(64))
        val scoped = legacy.copy(sequence = 21, data = buildJsonObject {
            legacy.data.forEach { (key, value) -> put(key, value) }
            put("goal_id", "goal-a")
        })
        assertEquals(21L, projectRequirementEvidenceLinks(sequenceOf(scoped), todos, "goal-a").single().eventSequence)
        assertTrue(projectRequirementEvidenceLinks(sequenceOf(scoped), todos, "goal-b").isEmpty())
        assertTrue(projectRequirementEvidenceLinks(sequenceOf(legacy), todos, "goal-b").isEmpty())
        assertEquals(20L, projectRequirementEvidenceLinks(sequenceOf(legacy), todos, null).single().eventSequence)
    }
}
