package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkArtifactProjectionTest {
    private fun event(seq: Long, path: String, error: Boolean = false) =
        LocalSessionEventLog.Event(
            sequence = seq, type = "tool/result", createdAt = seq,
            data = buildJsonObject {
                put("id", "call-$seq")
                put("path", path)
                put("is_error", error)
            },
        )

    @Test fun onlySuccessfulResultsProduceArtifacts() {
        val artifacts = projectLocalWorkArtifacts(listOf(
            event(1, "reports/one.md"),
            event(2, "reports/failed.md", error = true),
            event(3, "../escape.md"),
            event(4, "reports/one.md"),
        ))
        assertEquals(1, artifacts.size)
        assertEquals(4L, artifacts.single().asOfSequence)
        assertEquals("reports/one.md", artifacts.single().reference)
    }

    @Test fun declarationsCannotPretendToBeResults() {
        val declaration = event(9, "unconfirmed.md").copy(type = "tool/call")
        assertTrue(projectLocalWorkArtifacts(listOf(declaration)).isEmpty())
    }

    @Test fun completedWorkspaceResultsBecomeVisibleWithoutSyntheticPathFields() {
        val succeeded = LocalSessionEventLog.Event(
            sequence = 12, type = "tool/result", createdAt = 12,
            data = buildJsonObject {
                put("id", "call-12")
                put("name", "write_file")
                put("content", "已写入 reports/final.md（17 字节）")
                put("is_error", false)
            },
        )
        val artifacts = projectLocalWorkArtifacts(listOf(succeeded))
        assertEquals(1, artifacts.size)
        assertEquals("reports/final.md", artifacts.single().reference)
        assertEquals("call-12", artifacts.single().sourceCallId)
    }

    @Test fun failedOrUntrustedToolTextCannotFabricateArtifacts() {
        fun emitted(seq: Long, name: String, content: String, failed: Boolean = false) =
            LocalSessionEventLog.Event(
                sequence = seq, type = "tool/result", createdAt = seq,
                data = buildJsonObject {
                    put("id", "call-$seq")
                    put("name", name)
                    put("content", content)
                    put("is_error", failed)
                },
            )
        val artifacts = projectLocalWorkArtifacts(listOf(
            emitted(1, "read_file", "已写入 fake.md（10 字节）"),
            emitted(2, "write_file", "写入失败：blocked.md"),
            emitted(3, "present", "成果已确认：failed.md（10 字节）", failed = true),
            emitted(4, "edit_file", "已编辑 ../escape.md"),
            emitted(5, "present", "成果已确认：docs/confirmed.md（8 字节）"),
        ))
        assertEquals(listOf("docs/confirmed.md"), artifacts.map { it.reference })
    }

}
