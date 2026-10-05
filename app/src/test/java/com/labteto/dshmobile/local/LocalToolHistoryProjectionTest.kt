package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.WORK_TOOL_INLINE_BYTES
import com.labteto.dshmobile.local.model.projectStaleToolResults
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolHistoryProjectionTest {
    @Test
    fun staleLargeToolResultsShrinkWhileRecentResultsStayVerbatim() {
        val history = buildList {
            add(buildJsonObject { put("role", "system"); put("content", "rules") })
            repeat(6) { index ->
                add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", "call-$index")
                    put("content", "x".repeat(12_000) + "-$index")
                })
            }
        }
        val budget = LocalHistoryBudget(
            maxHistoryChars = 500_000,
            tailChars = 200_000,
            maxSummaryChars = 10_000,
            maxToolResultChars = 50_000,
            maxHistoryTokens = 200_000,
            tailTokens = 40_000,
        )

        val projected = projectStaleToolResults(history, budget)

        assertNotNull(projected)
        assertTrue(projected!!.estimatedTokensAfter < projected.estimatedTokensBefore)
        assertTrue(projected.messages[1]["content"]!!.jsonPrimitive.content.contains("tool_output_read"))
        assertEquals(history.last(), projected.messages.last())
    }
    @Test
    fun recentProtectionOnlyKeepsAlreadyBoundedToolResultsVerbatim() {
        val history = buildList {
            add(buildJsonObject { put("role", "system"); put("content", "rules") })
            add(buildJsonObject {
                put("role", "tool")
                put("tool_call_id", "old-large")
                put("content", "x".repeat(12_000))
            })
            add(buildJsonObject {
                put("role", "tool")
                put("tool_call_id", "recent-bounded")
                put("content", "y".repeat(3_000))
            })
            add(buildJsonObject {
                put("role", "tool")
                put("tool_call_id", "recent-large")
                put("content", "z".repeat(12_000))
            })
        }
        val budget = LocalHistoryBudget(
            maxHistoryChars = 500_000,
            tailChars = 200_000,
            maxSummaryChars = 10_000,
            maxToolResultChars = 4_096,
            maxHistoryTokens = 200_000,
            tailTokens = 40_000,
            maxToolResultTokens = 1_600,
        )

        val projected = projectStaleToolResults(
            history = history,
            budget = budget,
            keepRecentToolResults = 2,
            protectedRecentMaxBytes = WORK_TOOL_INLINE_BYTES,
        )

        assertNotNull(projected)
        assertEquals(history[2], projected!!.messages[2])
        assertTrue(
            projected.messages[3]["content"]!!.jsonPrimitive.content
                .contains("旧工具结果已从实时模型上下文衰减"),
        )
    }

}
