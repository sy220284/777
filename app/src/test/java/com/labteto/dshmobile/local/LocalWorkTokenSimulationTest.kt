package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkTokenSimulationTest {
    @Test
    fun shortWorkTaskStaysVerbatimWithoutPrematureCheckpointing() {
        val history = buildList {
            add(message("system", "系统规则"))
            repeat(10) { step ->
                add(message("user", "第${step}步：" + "需求".repeat(80)))
                add(message("assistant", "处理第${step}步：" + "分析".repeat(120)))
            }
        }

        val projected = projection(history)

        assertFalse(projected.projected)
        assertEquals(history, projected.messages)
        assertEquals(projected.estimatedTokensBefore, projected.estimatedTokensAfter)
    }

    @Test
    fun workLengthMatrixKeepsLongTasksBoundedAndCutsReplayGrowth() {
        val twenty = simulate(20)
        val fifty = simulate(50)
        val hundred = simulate(100)
        val twoHundred = simulate(200)

        assertTrue(twenty.projectedCumulative < twenty.rawCumulative)
        assertTrue(fifty.projectedCumulative * 100 < fifty.rawCumulative * 85)
        assertTrue(hundred.projectedCumulative * 100 < hundred.rawCumulative * 75)
        assertTrue(twoHundred.projectedCumulative * 100 < twoHundred.rawCumulative * 65)

        assertTrue(fifty.projectedPeak <= 40_000)
        assertTrue(hundred.projectedPeak <= 40_000)
        assertTrue(twoHundred.projectedPeak <= 40_000)
        assertTrue(twoHundred.projectedLast <= 32_000)
    }

    @Test
    fun burstOfLargeToolOutputsKeepsOnlyTwoRecentPayloadsHot() {
        val history = buildList {
            add(message("system", "系统规则"))
            add(message("user", "检查十二组大结果"))
            repeat(12) { index ->
                add(message("assistant", "读取结果$index"))
                add(toolMessage(index, "原始-$index-" + "大结果".repeat(4_000)))
            }
            add(message("user", "根据最新结果继续"))
        }
        val before = history.sumOf { estimateModelTokens(it.toString()) }

        val projected = projection(history)
        val tools = projected.messages.filter {
            it["role"]?.jsonPrimitive?.contentOrNull == "tool"
        }

        assertTrue(projected.projected)
        assertEquals(12, tools.size)
        assertTrue(projected.estimatedTokensAfter * 100 < before * 45)
        assertEquals(
            10,
            tools.count {
                it["content"]?.jsonPrimitive?.contentOrNull
                    ?.contains("旧工具结果已从实时模型上下文衰减") == true
            },
        )
        tools.takeLast(2).forEach { tool ->
            assertFalse(
                tool["content"]?.jsonPrimitive?.contentOrNull
                    ?.contains("旧工具结果已从实时模型上下文衰减") == true,
            )
        }
    }

    @Test
    fun staleToolPayloadRetainsRecoverableCallReference() {
        val history = buildList {
            add(message("system", "系统规则"))
            add(message("user", "分析旧工具结果"))
            repeat(6) { index ->
                add(message("assistant", "调用工具$index"))
                add(toolMessage(index, "结果-$index-" + "正文".repeat(3_000)))
            }
            add(message("user", "继续"))
        }

        val projected = projection(history)
        val stale = projected.messages.first {
            it["role"]?.jsonPrimitive?.contentOrNull == "tool" &&
                it["tool_call_id"]?.jsonPrimitive?.contentOrNull == "call-0"
        }
        val content = stale["content"]?.jsonPrimitive?.contentOrNull.orEmpty()

        assertTrue(content.contains("tool_output_read(call_id=call-0)"))
        assertEquals("call-0", stale["tool_call_id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun repeatedWorkWindowsKeepActiveConstraintsAcrossFiveCompactionGenerations() {
        var history = mutableListOf(message("system", "系统规则"))
        val active = LocalStructuredWorkState(
            goals = listOf("完成 Token 优化并保证任务正确"),
            constraints = listOf(
                "必须保持任务执行能力",
                "禁止通过精简功能换取 Token",
                "PR 标题和描述必须使用中文",
                "完整工具结果必须保持可恢复",
            ),
            decisions = listOf(
                "采用当前工作状态加最近因果链",
                "旧历史退出热序列后按需恢复",
            ),
            failures = listOf(
                "全量历史重发造成二次增长",
                "重复 bash 探查造成无效膨胀",
            ),
            unfinished = listOf("完成全部回归验证"),
        )

        repeat(5) { generation ->
            repeat(16) { index ->
                history += message(
                    "user",
                    "第${generation}代阶段任务-$index-" + "旧需求".repeat(500),
                )
                history += message(
                    "assistant",
                    "第${generation}代处理进展-$index-" + "旧分析".repeat(500),
                )
            }
            history += message("user", "继续第${generation}代最新任务")
            val projected = projectWorkRequestContext(
                messages = history,
                tools = JsonArray(emptyList()),
                compactor = LocalHistoryCompactor(),
                operationalLimitTokens = 678_464,
                structuredWorkState = if (generation == 0) active else null,
            )
            assertTrue(projected.projected)
            history = projected.messages.toMutableList()
        }

        val checkpoint = LocalWorkCheckpoint.latestFrom(history)
        assertNotNull(checkpoint)
        checkpoint ?: return
        assertTrue(checkpoint.constraints.contains("必须保持任务执行能力"))
        assertTrue(checkpoint.constraints.contains("禁止通过精简功能换取 Token"))
        assertTrue(checkpoint.constraints.contains("PR 标题和描述必须使用中文"))
        assertTrue(checkpoint.constraints.contains("完整工具结果必须保持可恢复"))
        assertTrue(checkpoint.decisions.any { it.contains("当前工作状态") })
        assertTrue(checkpoint.failures.any { it.contains("二次增长") })
        assertTrue(checkpoint.unfinished.contains("完成全部回归验证"))
    }

    private fun simulate(steps: Int): SimulationResult {
        val history = mutableListOf(message("system", "系统规则"))
        var rawCumulative = 0L
        var projectedCumulative = 0L
        var projectedPeak = 0
        var projectedLast = 0

        repeat(steps) { step ->
            history += message("user", "第${step}步继续当前任务：" + "需求".repeat(120))
            history += message("assistant", "第${step}步分析并推进：" + "分析".repeat(220))
            history += toolMessage(step, "工具结果-$step-" + "结果".repeat(420))

            val rawTokens = history.sumOf { estimateModelTokens(it.toString()) }
            val projected = projectWorkRequestContext(
                messages = history,
                tools = JsonArray(emptyList()),
                compactor = LocalHistoryCompactor(),
                operationalLimitTokens = 678_464,
                structuredWorkState = LocalStructuredWorkState(
                    goals = listOf("完成长任务"),
                    constraints = listOf("必须保持任务正确性"),
                    unfinished = listOf("继续当前步骤"),
                ),
            )
            rawCumulative += rawTokens
            projectedCumulative += projected.estimatedTokensAfter
            projectedPeak = maxOf(projectedPeak, projected.estimatedTokensAfter)
            projectedLast = projected.estimatedTokensAfter
        }
        return SimulationResult(
            rawCumulative = rawCumulative,
            projectedCumulative = projectedCumulative,
            projectedPeak = projectedPeak,
            projectedLast = projectedLast,
        )
    }

    private fun projection(history: List<JsonObject>): LocalWorkRequestProjection =
        projectWorkRequestContext(
            messages = history,
            tools = JsonArray(emptyList()),
            compactor = LocalHistoryCompactor(),
            operationalLimitTokens = 678_464,
        )

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun toolMessage(index: Int, content: String): JsonObject = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", "call-$index")
        put("content", content)
    }

    private data class SimulationResult(
        val rawCumulative: Long,
        val projectedCumulative: Long,
        val projectedPeak: Int,
        val projectedLast: Int,
    )
}
