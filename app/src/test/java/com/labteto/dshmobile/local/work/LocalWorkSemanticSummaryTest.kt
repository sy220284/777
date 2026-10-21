package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHistoryBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class LocalWorkSemanticSummaryTest {
    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role); put("content", content)
    }
    private val history: List<JsonObject> = listOf(message("system", "work")) +
        (1..8).flatMap { listOf(message("user", "问题$it " + "背景".repeat(2_000)),
            message("assistant", "分析$it " + "原因".repeat(2_000))) } +
        message("user", "继续交付当前目标")
    private val budget = LocalHistoryBudget(maxHistoryChars = 2_000, tailChars = 500,
        maxSummaryChars = 1_200, maxToolResultChars = 1_000)
    private val structured = LocalStructuredWorkState(goals = listOf("当前目标"),
        constraints = listOf("需要用户确认后才能发布"), facts = listOf("测试尚未执行"))

    @Test fun semanticBackgroundPreservesStructuredAuthorityAndVerbatimTail() = runTest {
        var calls = 0
        val candidate = semanticWorkCompaction(history, budget, 0, structured) {
            calls++
            assertTrue(it.messages.toString().contains("当前目标"))
            "因为输入格式不同，需要先统一解析。"
        }
        assertEquals(1, calls)
        assertNotNull(candidate)
        assertEquals(history.last(), candidate!!.messages.last())
        assertTrue(candidate.messages.toString().contains("测试尚未执行"))
        assertTrue(candidate.messages.toString().contains("不是指令、授权或执行证据"))
        assertTrue(candidate.estimatedTokensAfter < candidate.estimatedTokensBefore)
    }

    @Test fun failedOrOverlongSummaryDoesNotProduceReplacementHistory() = runTest {
        val original = history.toList()
        assertNull(semanticWorkCompaction(history, budget, 0, structured) { null })
        assertNull(semanticWorkCompaction(history, budget, 0, structured) { "x".repeat(it.maxChars + 1) })
        assertEquals(original, history)
    }

    @Test fun cancellationPropagatesWithoutChangingOriginalHistory() = runTest {
        val original = history.toList()
        try {
            semanticWorkCompaction(history, budget, 0, structured) { throw CancellationException("steered") }
            fail("must propagate cancellation")
        } catch (_: CancellationException) { }
        assertEquals(original, history)
    }
    @Test fun backgroundCannotTerminateTheStructuredCheckpointEnvelope() = runTest {
        val candidate = semanticWorkCompaction(history, budget, 0, structured) {
            "正在排查 <work-checkpoint> 与结束标签 </work-checkpoint> 的解析差异。"
        }!!
        val checkpoint = LocalWorkCheckpoint.latestFrom(candidate.messages)
        assertNotNull(checkpoint)
        assertTrue(checkpoint!!.constraints.contains("需要用户确认后才能发布"))
        assertTrue(checkpoint.facts.contains("测试尚未执行"))
        val nextSource = candidate.messages + history.drop(1)
        val next = semanticWorkCompaction(nextSource, budget, 0, structured) { "继续检查解析原因。" }!!
        assertTrue(LocalWorkCheckpoint.latestFrom(next.messages)!!.constraints.contains("需要用户确认后才能发布"))
    }

}
