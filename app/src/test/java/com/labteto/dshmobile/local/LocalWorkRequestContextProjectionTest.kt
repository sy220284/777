package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkRequestContextProjectionTest {
    @Test
    fun largeWorkHistoryBecomesCheckpointPlusRecentTail() {
        val history = buildList {
            add(message("system", "系统规则"))
            repeat(20) { index ->
                add(message("user", "旧任务-$index-" + "旧".repeat(2_500)))
                add(message("assistant", "旧进展-$index-" + "进".repeat(2_500)))
            }
            add(message("user", "当前继续处理最新任务"))
        }

        val projected = projectWorkRequestContext(
            messages = history,
            tools = JsonArray(emptyList()),
            compactor = LocalHistoryCompactor(),
            operationalLimitTokens = 678_464,
        )

        assertTrue(projected.projected)
        assertTrue(projected.estimatedTokensBefore > workRequestProjectionTriggerTokens(678_464))
        assertTrue(projected.estimatedTokensAfter < projected.estimatedTokensBefore)
        assertTrue(projected.messages.size < history.size)
        assertTrue(projected.messages.any { it["content"].toString().contains("<work-checkpoint>") })
        assertTrue(projected.messages.last()["content"].toString().contains("当前继续处理最新任务"))
    }

    @Test
    fun requestProjectionPreservesLeadingRuntimeSystemContextVerbatim() {
        val runtimeContext = "当前运行上下文：必须保留本轮约束与工作区状态"
        val history = buildList {
            add(message("system", "基础系统规则"))
            add(message("system", runtimeContext))
            repeat(20) { index ->
                add(message("user", "旧任务-$index-" + "旧".repeat(2_500)))
                add(message("assistant", "旧进展-$index-" + "进".repeat(2_500)))
            }
            add(message("user", "继续最新任务"))
        }

        val projected = projectWorkRequestContext(
            messages = history,
            tools = JsonArray(emptyList()),
            compactor = LocalHistoryCompactor(),
            operationalLimitTokens = 678_464,
        )

        assertTrue(projected.projected)
        assertEquals(history[0], projected.messages[0])
        assertEquals(history[1], projected.messages[1])
        assertTrue(projected.messages[1]["content"].toString().contains(runtimeContext))
        assertTrue(projected.messages.last()["content"].toString().contains("继续最新任务"))
    }

    @Test
    fun smallWorkHistoryStaysVerbatim() {
        val history = listOf(
            message("system", "系统规则"),
            message("user", "检查当前文件"),
            message("assistant", "已检查"),
        )

        val projected = projectWorkRequestContext(
            messages = history,
            tools = JsonArray(emptyList()),
            compactor = LocalHistoryCompactor(),
            operationalLimitTokens = 678_464,
        )

        assertFalse(projected.projected)
        assertEquals(history, projected.messages)
        assertEquals(projected.estimatedTokensBefore, projected.estimatedTokensAfter)
    }

    @Test
    fun steadyStateBudgetTightensExistingPersistentCompactionOnlyAfterTrigger() {
        val base = LocalHistoryBudget(
            maxHistoryChars = 500_000,
            tailChars = 240_000,
            maxSummaryChars = 16_000,
            maxToolResultChars = 50_000,
            maxHistoryTokens = 678_464,
            tailTokens = 160_000,
            maxToolResultTokens = 16_000,
        )

        val small = workSteadyStateHistoryBudget(
            base = base,
            currentHistoryTokens = 40_000,
            extraTokens = 10_000,
        )
        assertEquals(base, small)

        val large = workSteadyStateHistoryBudget(
            base = base,
            currentHistoryTokens = 70_000,
            extraTokens = 20_000,
        )
        assertEquals(28_000, large.maxHistoryTokens)
        assertTrue(requireNotNull(large.tailTokens) <= 10_000)
        assertTrue(large.maxToolResultTokens <= 2_400)
    }


    @Test
    fun staleToolPayloadsLeaveHotRequestBeforeHistoryNeedsFullCompaction() {
        val history = buildList {
            add(message("system", "系统规则"))
            add(message("user", "检查几个文件"))
            repeat(5) { index ->
                add(message("assistant", "调用工具$index"))
                add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", "call-$index")
                    put("content", "结果-$index-" + "大".repeat(4_000))
                })
            }
            add(message("user", "继续处理当前结果"))
        }
        val before = history.sumOf { estimateModelTokens(it.toString()) }

        val projected = projectWorkRequestContext(
            messages = history,
            tools = JsonArray(emptyList()),
            compactor = LocalHistoryCompactor(),
            operationalLimitTokens = 678_464,
        )

        assertTrue(projected.projected)
        assertEquals(0, projected.omittedMessages)
        assertTrue(projected.estimatedTokensAfter < before)
        val toolContents = projected.messages
            .filter { it["role"].toString().trim('"') == "tool" }
            .map { it["content"].toString() }
        assertTrue(toolContents.take(3).all { it.contains("旧工具结果已从实时模型上下文衰减") })
        assertTrue(toolContents.takeLast(2).none { it.contains("旧工具结果已从实时模型上下文衰减") })
    }

    @Test
    fun workProjectionCarriesStructuredConstraintsIntoCheckpoint() {
        val history = buildList {
            add(message("system", "系统规则"))
            repeat(18) { index ->
                add(message("user", "阶段任务-$index-" + "旧".repeat(2_000)))
                add(message("assistant", "阶段进展-$index-" + "进".repeat(2_000)))
            }
            add(message("user", "继续当前实现"))
        }
        val structured = LocalStructuredWorkState(
            goals = listOf("降低 Work Token 消耗"),
            constraints = listOf("必须保持任务执行能力", "禁止通过删减功能换取 Token"),
            decisions = listOf("采用当前工作状态加近期因果链"),
            failures = listOf("全量历史每步重发导致输入二次增长"),
            unfinished = listOf("完成回归测试"),
        )

        val projected = projectWorkRequestContext(
            messages = history,
            tools = JsonArray(emptyList()),
            compactor = LocalHistoryCompactor(),
            operationalLimitTokens = 678_464,
            structuredWorkState = structured,
        )

        val checkpoint = requireNotNull(LocalWorkCheckpoint.latestFrom(projected.messages))
        assertTrue(checkpoint.constraints.contains("必须保持任务执行能力"))
        assertTrue(checkpoint.constraints.contains("禁止通过删减功能换取 Token"))
        assertTrue(checkpoint.decisions.any { it.contains("当前工作状态") })
        assertTrue(checkpoint.failures.any { it.contains("二次增长") })
    }

    @Test
    fun targetAndTriggerStayInsideSmallModelOperationalLimit() {
        val limit = 16_000
        val target = workRequestProjectionTargetTokens(limit)
        val trigger = workRequestProjectionTriggerTokens(limit)

        assertTrue(target in 1..limit)
        assertTrue(trigger in target..limit)
    }

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}
