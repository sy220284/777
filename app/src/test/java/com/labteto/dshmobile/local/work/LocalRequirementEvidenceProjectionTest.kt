package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
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
}
