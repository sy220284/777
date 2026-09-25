package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun compactsOnTokenPressureEvenWhenCharacterBudgetIsStillAvailable() {
        val history = listOf(
            message("system", "系统"),
            message("user", "旧内容" + "汉".repeat(2_000)),
            message("assistant", "旧答复" + "字".repeat(2_000)),
            message("user", "最近请求" + "新".repeat(120)),
            message("assistant", "最近答复" + "新".repeat(120)),
        )
        val budget = LocalHistoryBudget(
            maxHistoryChars = 100_000,
            tailChars = 2_000,
            maxSummaryChars = 2_000,
            maxToolResultChars = 10_000,
            maxHistoryTokens = 1_500,
            tailTokens = 300,
            maxToolResultTokens = 2_000,
        )
        val compaction = LocalHistoryCompactor().compact(history, budget = budget)
            ?: error("expected token-pressure compaction")

        assertTrue(compaction.estimatedTokensBefore > budget.maxHistoryTokens!!)
        assertTrue(compaction.omittedMessages > 0)
        assertTrue(compaction.estimatedTokensAfter < compaction.estimatedTokensBefore)
    }

    @Test
    fun refusesCompactionWhenSummaryWouldNotReduceContext() {
        val history = listOf(
            message("system", "系统"),
            message("user", "旧目标" + "旧".repeat(120)),
            message("assistant", "旧答复" + "旧".repeat(120)),
            message("user", "最近请求" + "新".repeat(120)),
            message("assistant", "最近答复" + "新".repeat(120)),
        )
        val budget = LocalHistoryBudget(
            maxHistoryChars = 100_000,
            tailChars = 2_000,
            maxSummaryChars = 2_000,
            maxToolResultChars = 10_000,
            maxHistoryTokens = 300,
            tailTokens = 250,
            maxToolResultTokens = 2_000,
        )

        assertNull(LocalHistoryCompactor().compact(history, budget = budget))
    }

    @Test
    fun chatCompactionUsesContinuityLanguageInsteadOfWorkLanguage() {
        val history = listOf(
            message("system", "聊天系统"),
            message("user", "之前我们约好周末去海边。" + "旧".repeat(240)),
            message("assistant", "我还记得你说想看日落。" + "旧".repeat(240)),
            message("user", "继续刚才的话题。" + "新".repeat(120)),
            message("assistant", "好。" + "新".repeat(120)),
        )
        val compaction = LocalHistoryCompactor(maxHistoryChars = 400, tailChars = 260)
            .compact(history, summaryMode = LocalHistorySummaryMode.CHAT)
            ?: error("expected chat compaction")

        assertTrue(compaction.summary.contains("较早聊天"))
        assertTrue(compaction.summary.contains("较早用户表达与事件"))
        assertTrue(compaction.summary.contains("较早角色回应与互动"))
        assertFalse(compaction.summary.contains("当前目标、计划"))
    }

    @Test
    fun retainedToolTextKeepsSupplementaryCharactersWhole() {
        val source = "前".repeat(40) + "😀" + "后".repeat(40)
        val retained = retainTextForModel(source, maxTokens = 12, maxChars = 48)

        assertTrue(retained.truncated)
        assertFalse(retained.text.anyIndexed { index, ch ->
            Character.isHighSurrogate(ch) &&
                (index + 1 >= retained.text.length || !Character.isLowSurrogate(retained.text[index + 1]))
        })
        assertFalse(retained.text.anyIndexed { index, ch ->
            Character.isLowSurrogate(ch) &&
                (index == 0 || !Character.isHighSurrogate(retained.text[index - 1]))
        })
    }

    private fun String.anyIndexed(predicate: (Int, Char) -> Boolean): Boolean {
        for (index in indices) if (predicate(index, this[index])) return true
        return false
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
