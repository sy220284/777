package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHistoryCompactorTest {
    @Test
    fun leavesSmallHistoryUntouched() {
        val compactor = LocalHistoryCompactor(maxHistoryChars = 10_000, tailChars = 1_000)
        assertNull(compactor.compact(listOf(message("system", "系统"), message("user", "你好"))))
    }

    @Test
    fun preservesRecentTurnAndSummarizesOlderIntent() {
        val pad = "甲".repeat(120)
        val history = listOf(
            message("system", "系统"),
            message("user", "旧目标：修复会话恢复。" + pad),
            message("assistant", "阶段结论：先补事件日志。" + pad),
            toolCallingAssistant("read"),
            message("tool", "工具输出" + pad),
            message("user", "最新请求：继续完成剩余问题。" + pad),
            message("assistant", "正在继续处理。" + pad),
        )
        val compaction = LocalHistoryCompactor(maxHistoryChars = 300, tailChars = 260)
            .compact(history) ?: error("expected compaction")

        assertTrue(compaction.omittedMessages > 0)
        assertTrue(compaction.summary.contains("旧目标：修复会话恢复"))
        assertTrue(compaction.summary.contains("阶段结论：先补事件日志"))
        assertTrue(compaction.summary.contains("read"))
        assertEquals("system", compaction.messages.first()["role"].toString().trim('"'))
        assertTrue(compaction.messages.any { it["content"].toString().contains("最新请求") })
        assertTrue(compaction.summary.length <= 16_000)
    }

    @Test
    fun summaryIsBoundedForVeryLargeOlderMessages() {
        val huge = "长".repeat(20_000)
        val history = listOf(
            message("system", "系统"),
            message("user", huge),
            message("assistant", huge),
            message("user", "最近请求" + "新".repeat(400)),
            message("assistant", "最近答复" + "新".repeat(400)),
        )
        val compaction = LocalHistoryCompactor(
            maxHistoryChars = 1_000,
            tailChars = 700,
            maxSummaryChars = 2_000,
        ).compact(history) ?: error("expected compaction")

        assertTrue(compaction.summary.length <= 2_000)
    }

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun toolCallingAssistant(name: String): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", "准备调用工具")
        put("tool_calls", kotlinx.serialization.json.buildJsonArray {
            add(buildJsonObject {
                put("function", buildJsonObject { put("name", name) })
            })
        })
    }
}
