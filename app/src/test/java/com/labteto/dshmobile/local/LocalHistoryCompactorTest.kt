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
        assertEquals("user", compaction.messages[1]["role"].toString().trim('"'))
        assertTrue(compaction.messages[1]["content"].toString().contains("<compacted-summary>"))
        assertTrue(compaction.messages.any { it["content"].toString().contains("最新请求") })
        assertTrue(compaction.summary.length <= 16_000)
    }

    @Test
    fun workCompactionBuildsStructuredCheckpointWithoutInventingFacts() {
        val pad = "旧".repeat(2_000)
        val history = listOf(
            message("system", "系统"),
            message("user", "目标：完成无限会话分页。" + pad),
            message("user", "约束：必须保持旧会话兼容，禁止新增第二份完整历史。" + pad),
            message("assistant", "已确认方案：保留 Session Event 为事实源。" + pad),
            message("user", "下一步继续处理运行态与界面态拆分。" + pad),
            message("assistant", "失败尝试：旧方案会造成快照线性膨胀，需要回退。" + pad),
            message("user", "最新请求" + "新".repeat(120)),
            message("assistant", "正在继续" + "新".repeat(120)),
        )

        val compaction = LocalHistoryCompactor(
            maxHistoryChars = 500,
            tailChars = 300,
            maxSummaryChars = 3_000,
        )
            .compact(history, summaryMode = LocalHistorySummaryMode.WORK)
            ?: error("expected structured compaction")

        assertTrue(compaction.summary.contains("结构化提取式检查点"))
        assertTrue(compaction.summary.contains("目标与需求："))
        assertTrue(compaction.summary.contains("约束与边界："))
        assertTrue(compaction.summary.contains("关键决定与阶段结论："))
        assertTrue(compaction.summary.contains("失败尝试与风险："))
        assertTrue(compaction.summary.contains("未完成事项："))
        assertTrue(compaction.summary.contains("完成无限会话分页"))
        assertTrue(compaction.summary.contains("保持旧会话兼容"))
        assertTrue(compaction.summary.contains("Session Event 为事实源"))
        assertTrue(compaction.summary.contains("快照线性膨胀"))
        assertTrue(compaction.summary.contains("运行态与界面态拆分"))
        assertFalse(compaction.summary.contains("不存在的结论"))
        assertTrue(compaction.estimatedTokensAfter < compaction.estimatedTokensBefore)
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
            message("user", "之前我们约好周末去海边。" + "旧".repeat(4_000)),
            message("assistant", "我还记得你说想看日落。" + "旧".repeat(4_000)),
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
        assertTrue(compaction.estimatedTokensAfter < compaction.estimatedTokensBefore)
    }

    @Test
    fun overflowCompactionPreservesHeadAndTailSystemOrderingAndShrinksRequest() {
        val history = listOf(
            message("system", "固定系统规则"),
            message("system", "当前角色设定"),
            message("user", "很早的事情" + "旧".repeat(4_000)),
            message("assistant", "很早的回应" + "旧".repeat(4_000)),
            message("system", "当前关系状态"),
            message("user", "现在继续聊" + "新".repeat(500)),
            message("assistant", "继续" + "新".repeat(500)),
        )

        val compaction = LocalHistoryCompactor()
            .compactForOverflow(history, LocalHistorySummaryMode.CHAT)
            ?: error("expected overflow compaction")

        assertTrue(compaction.messages[0]["content"].toString().contains("固定系统规则"))
        assertTrue(compaction.messages[1]["content"].toString().contains("当前角色设定"))

        val summaryIndex = compaction.messages.indexOfFirst {
            it["content"].toString().contains("<compacted-summary>")
        }
        val relationIndex = compaction.messages.indexOfFirst {
            it["content"].toString().contains("当前关系状态")
        }
        val currentUserIndex = compaction.messages.indexOfFirst {
            it["content"].toString().contains("现在继续聊")
        }
        assertTrue(summaryIndex >= 2)
        assertTrue(relationIndex > summaryIndex)
        assertTrue(currentUserIndex > relationIndex)
        assertEquals("user", compaction.messages[summaryIndex]["role"].toString().trim('"'))
        assertTrue(compaction.estimatedTokensAfter < compaction.estimatedTokensBefore)
    }

    @Test
    fun overflowCompactionCanBeAppliedBackToDurableHistory() {
        val history = mutableListOf(
            message("system", "系统"),
            message("user", "旧请求" + "旧".repeat(5_000)),
            message("assistant", "旧答复" + "旧".repeat(5_000)),
            message("user", "新请求" + "新".repeat(600)),
            message("assistant", "新答复" + "新".repeat(600)),
        )
        val before = history.sumOf { estimateModelTokens(it.toString()) }

        val compaction = applyOverflowCompaction(
            history = history,
            compactor = LocalHistoryCompactor(),
            summaryMode = LocalHistorySummaryMode.WORK,
        ) ?: error("expected durable overflow compaction")

        val after = history.sumOf { estimateModelTokens(it.toString()) }
        assertEquals(compaction.messages, history)
        assertTrue(history.any { it["content"].toString().contains("<compacted-summary>") })
        assertTrue(after < before)
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
