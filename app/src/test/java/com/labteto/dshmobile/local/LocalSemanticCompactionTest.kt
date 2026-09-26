package com.labteto.dshmobile.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSemanticCompactionTest {
    @Test
    fun requestKeepsFallbackAndBoundsOlderHistory() {
        val history = buildList {
            repeat(40) { index ->
                add(message(if (index % 2 == 0) "user" else "assistant", "row-$index " + "x".repeat(5_000)))
            }
        }
        val compaction = LocalHistoryCompaction(
            messages = listOf(message("system", "s"), message("user", "<compacted-summary>\nfallback\n</compacted-summary>")),
            omittedMessages = history.size,
            summary = "目标与需求：fallback",
            estimatedTokensBefore = 100_000,
            estimatedTokensAfter = 20_000,
            omittedHistory = history,
        )

        val request = semanticCompactionRequest(compaction)
        val user = request.last()["content"]!!.jsonPrimitive.content

        assertTrue(user.contains("本地提取式兜底摘要"))
        assertTrue(user.length < 150_000)
        assertFalse(user.contains("data:image"))
    }

    @Test
    fun semanticSummaryRequiresStructuredHeadings() {
        assertNull(sanitizeSemanticCompactionSummary("一句随意总结"))
        assertNotNull(
            sanitizeSemanticCompactionSummary(
                """
                目标与需求：完成同步
                约束与边界：保留兼容层
                关键决定与阶段结论：采用双协议
                失败尝试与风险：无
                未完成事项：补测试
                其他阶段进展：已完成路由
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun refinedSummaryNeverExpandsAcceptedCompaction() {
        val compactor = LocalHistoryCompactor()
        val original = LocalHistoryCompaction(
            messages = listOf(
                message("system", "系统"),
                message("user", "<compacted-summary>\n" + "旧".repeat(2_000) + "\n</compacted-summary>"),
                message("user", "继续"),
            ),
            omittedMessages = 10,
            summary = "旧".repeat(2_000),
            estimatedTokensBefore = 50_000,
            estimatedTokensAfter = 10_000,
        )

        assertNotNull(compactor.replaceSummary(original, "目标与需求：短摘要"))
        assertNull(compactor.replaceSummary(original, "新".repeat(20_000)))
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}
