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
        assertEquals(64_000, large.maxHistoryTokens)
        assertTrue(requireNotNull(large.tailTokens) <= 24_000)
        assertTrue(large.maxToolResultTokens <= 8_000)
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
