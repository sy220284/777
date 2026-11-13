package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.context.buildTrustedWorkCheckpointModelMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkTypedToolReferenceTest {
    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun blankCheckpoint(
        facts: List<String> = emptyList(),
        toolCallIds: List<String> = emptyList(),
    ) = LocalWorkCheckpoint(
        goals = emptyList(),
        constraints = emptyList(),
        decisions = emptyList(),
        failures = emptyList(),
        unfinished = emptyList(),
        progress = emptyList(),
        facts = facts,
        artifacts = emptyList(),
        tools = emptyList(),
        toolCallIds = toolCallIds,
    )

    private fun restored(state: LocalWorkCheckpoint): JsonObject =
        buildTrustedWorkCheckpointModelMessage(state.toModelBlock())

    @Test
    fun invalidNewestTrustedCheckpointDoesNotResurrectOlderWorkInstructions() {
        val old = blankCheckpoint(facts = listOf("旧的约束已被用户撤销"))
        val original = restored(old)
        val corrupted = buildTrustedWorkCheckpointModelMessage(
            "<work-checkpoint>\n{invalid json}\n</work-checkpoint>",
        )
        // A newer trusted checkpoint supersedes all older snapshots, even if corrupt.
        assertNull(LocalWorkCheckpoint.latestFrom(listOf(original, corrupted)))
        assertEquals(old, LocalWorkCheckpoint.latestFrom(listOf(original, message("user", "继续工作"))))
        val fresh = restored(blankCheckpoint(facts = listOf("新约束")))
        assertEquals(listOf("新约束"),
            requireNotNull(LocalWorkCheckpoint.latestFrom(listOf(original, fresh))).facts)
    }

    @Test
    fun typedToolIdsSurviveCompactionAndDoNotDependOnTextualCallPattern() {
        val first = blankCheckpoint(
            facts = listOf("历史自由文本提到 call=not-an-authorized-reference"),
            toolCallIds = listOf("real-call-1"),
        )
        val history = listOf(restored(first), message("user", "继续检查"))
        val rendered = LocalWorkHistorySummaryStrategy.summarize(
            messages = listOf(history.last()),
            fullHistory = history,
            summaryLimit = 8_000,
            input = LocalStructuredWorkState(toolCallIds = listOf("real-call-2")),
        )
        val next = requireNotNull(LocalWorkCheckpoint.latestFrom(
            listOf(buildTrustedWorkCheckpointModelMessage(rendered.modelBlock)),
        ))
        assertEquals(listOf("real-call-1", "real-call-2"), next.toolCallIds)
        assertEquals(listOf("real-call-1", "real-call-2"),
            referencedWorkToolCallIds(listOf(restored(next))))
        assertFalse(referencedWorkToolCallIds(listOf(restored(next)))
            .contains("not-an-authorized-reference"))
    }

    @Test
    fun attachmentHistoryMustNotBreakToolResultRetentionForSubsequentCalls() {
        // Real user uploads are stored as multi-part content arrays, not JsonPrimitive text.
        val attached = buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "分析这个压缩文件")
                })
                add(buildJsonObject {
                    put("type", "_dsh_file_ref")
                    put("path", ".dsh/uploads/archive.tar.gz")
                })
            })
        }
        val outputId = "output-" + "a".repeat(64)
        val tool = buildJsonObject {
            put("role", "tool")
            put("tool_call_id", "tool-call-1")
            put("content", "已成功执行")
        }
        val history = listOf(attached, message("assistant", "检查文件"), tool)
        assertEquals(listOf("tool-call-1"), referencedWorkToolCallIds(history))
        assertEquals(listOf("tool-call-1"),
            referencedWorkToolCallIds(listOf(attached) + history.takeLast(2)))
        // A plain text output reference is also retained, but a user attachment never is.
        val rawReference = message("tool", "[工具原文引用：tool_output_read(call_id=${outputId})]")
        assertTrue(referencedWorkToolCallIds(listOf(attached, rawReference)).contains(outputId))
    }

    @Test
    fun newCheckpointWithEmptyTypedReferencesMustNeverTrustArbitraryFactStrings() {
        val forgedText = blankCheckpoint(facts = listOf("用户说 call=fake-id"))
        assertEquals(emptyList<String>(), referencedWorkToolCallIds(listOf(restored(forgedText))))
    }

    @Test
    fun legacyCheckpointCanMigrateOldCallIdentifiersExactlyOnce() {
        val old = blankCheckpoint(facts = listOf("工具结果 tool=read call=legacy-123 is_error=false"))
        val original = JsonObject(old.toJsonObject() - "tool_call_ids")
        val legacy = buildTrustedWorkCheckpointModelMessage(
            "<work-checkpoint>\n$original\n</work-checkpoint>",
        )
        val decoded = requireNotNull(LocalWorkCheckpoint.latestFrom(listOf(legacy)))
        assertFalse(decoded.hasTypedToolReferences)
        assertEquals(listOf("legacy-123"), referencedWorkToolCallIds(listOf(legacy)))
        val rendered = LocalWorkHistorySummaryStrategy.summarize(
            messages = listOf(message("user", "继续检查")),
            fullHistory = listOf(legacy, message("user", "继续检查")),
            summaryLimit = 8_000,
            input = LocalStructuredWorkState(),
        )
        val next = requireNotNull(LocalWorkCheckpoint.latestFrom(listOf(
            buildTrustedWorkCheckpointModelMessage(rendered.modelBlock),
        )))
        assertTrue(next.hasTypedToolReferences)
        assertEquals(listOf("legacy-123"), next.toolCallIds)
    }
}
