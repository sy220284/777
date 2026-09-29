package com.labteto.dshmobile.local

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageAnalyticsTest {
    @Test
    fun aggregatesModesDaysTurnsAndParentTaskWithoutDoubleCountingSubfields() {
        val zone = ZoneId.of("UTC")
        val day = Instant.parse("2026-09-29T08:00:00Z").toEpochMilli()
        val records = listOf(
            record(
                id = "chat-1",
                time = day,
                input = 100,
                output = 20,
                hit = 60,
                miss = 40,
                reasoning = 10,
                context = TokenUsageContext(
                    mode = LocalUsageMode.CHAT,
                    sessionId = "session-a",
                    sessionTitle = "角色聊天",
                    turnId = "turn-1",
                    runKind = LocalAgentRunKind.FOREGROUND.name.lowercase(),
                    action = TokenUsageAction.CHAT_REPLY,
                ),
            ),
            record(
                id = "chat-bg",
                time = day + 1_000,
                input = 50,
                output = 5,
                context = TokenUsageContext(
                    mode = LocalUsageMode.CHAT,
                    sessionId = "session-a",
                    sessionTitle = "角色聊天",
                    turnId = "turn-1",
                    runKind = LocalAgentRunKind.FOREGROUND.name.lowercase(),
                    action = TokenUsageAction.CHAT_STATE_REFRESH,
                ),
            ),
            record(
                id = "chat-repair",
                time = day + 1_500,
                input = 40,
                output = 8,
                context = TokenUsageContext(
                    mode = LocalUsageMode.CHAT,
                    sessionId = "session-a",
                    sessionTitle = "角色聊天",
                    turnId = "turn-1",
                    runKind = LocalAgentRunKind.FOREGROUND.name.lowercase(),
                    action = TokenUsageAction.CHAT_REPAIR,
                ),
            ),
            record(
                id = "chat-2",
                time = day + 2_000,
                input = 80,
                output = 15,
                context = TokenUsageContext(
                    mode = LocalUsageMode.CHAT,
                    sessionId = "session-a",
                    sessionTitle = "角色聊天",
                    turnId = "turn-2",
                    runKind = LocalAgentRunKind.FOREGROUND.name.lowercase(),
                    action = TokenUsageAction.CHAT_REPLY,
                ),
            ),
            record(
                id = "work-main",
                time = day + 3_000,
                input = 200,
                output = 30,
                context = TokenUsageContext(
                    mode = LocalUsageMode.WORK,
                    runId = "run-root",
                    runKind = LocalAgentRunKind.FOREGROUND.name.lowercase(),
                    taskLabel = "完善统计",
                    action = TokenUsageAction.WORK_MAIN,
                ),
            ),
            record(
                id = "work-child",
                time = day + 4_000,
                input = 120,
                output = 20,
                context = TokenUsageContext(
                    mode = LocalUsageMode.WORK,
                    runId = "run-child",
                    parentRunId = "run-root",
                    runKind = LocalAgentRunKind.SUBAGENT.name.lowercase(),
                    agentId = "sa-1",
                    taskLabel = "检查界面",
                    action = TokenUsageAction.WORK_SUBAGENT,
                ),
            ),
        )

        val snapshot = aggregateTokenUsageRecords(records.asSequence(), zone)

        assertEquals(688L, snapshot.tracked.totalTokens)
        assertEquals(318L, snapshot.chat.aggregate.totalTokens)
        assertEquals(2, snapshot.chat.turnCount)
        assertEquals(135L, snapshot.chat.averageInputPerTurn)
        assertEquals(11L, snapshot.chat.averageOutputPerTurn)
        assertEquals(87L, snapshot.chat.averageBackgroundPerTurn)

        assertEquals(1, snapshot.work.turnCount)
        assertEquals(320L, snapshot.work.averageInputPerTurn)
        assertEquals(230L, snapshot.work.mainTokens)
        assertEquals(140L, snapshot.work.subagentTokens)
        assertEquals(1, snapshot.tasks.size)
        assertEquals("run-root", snapshot.tasks.single().key)
        assertEquals("完善统计", snapshot.tasks.single().title)
        assertEquals(370L, snapshot.tasks.single().aggregate.totalTokens)

        // Cache and reasoning are diagnostic subdivisions and must never inflate totalTokens.
        assertEquals(60L, snapshot.tracked.cacheHitTokens)
        assertEquals(10L, snapshot.tracked.reasoningTokens)
        assertEquals(1, snapshot.days.size)
    }

    @Test
    fun promptBreakdownExplainsOneInputWithoutCreatingAnotherTotal() {
        val messages = listOf(
            buildJsonObject {
                put("role", "system")
                put("content", "固定系统规则")
            },
            buildJsonObject {
                put("role", "system")
                put("content", "【用户长期规则】保持中文回答")
            },
            buildJsonObject {
                put("role", "system")
                put("content", "【角色】测试角色\n【当前状态】自然")
            },
            buildJsonObject {
                put("role", "assistant")
                put("content", "上一轮回复")
            },
            buildJsonObject {
                put("role", "user")
                put("content", "本轮消息")
            },
        )
        val tools = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "function")
                    put("name", "read")
                },
            ),
        )

        val breakdown = estimatePromptBreakdown(messages, tools)

        assertTrue(breakdown.systemBaseTokens > 0)
        assertTrue(breakdown.memoryRuleTokens > 0)
        assertTrue(breakdown.personaStateTokens > 0)
        assertTrue(breakdown.historyTokens > 0)
        assertTrue(breakdown.currentUserTokens > 0)
        assertTrue(breakdown.toolDefinitionTokens > 0)
        assertEquals(
            (
                breakdown.systemBaseTokens +
                    breakdown.personaStateTokens +
                    breakdown.memoryRuleTokens +
                    breakdown.historyTokens +
                    breakdown.currentUserTokens +
                    breakdown.toolDefinitionTokens +
                    breakdown.otherSystemTokens
                ).toLong(),
            breakdown.estimatedInputTokens,
        )
    }

    private fun record(
        id: String,
        time: Long,
        input: Long,
        output: Long,
        hit: Long = 0,
        miss: Long = input - hit,
        reasoning: Long = 0,
        context: TokenUsageContext,
    ) = TokenUsageRecord(
        requestId = id,
        timestamp = time,
        model = "deepseek-flash",
        context = context,
        inputTokens = input,
        cacheHitTokens = hit,
        cacheMissTokens = miss,
        outputTokens = output,
        reasoningTokens = reasoning,
        reported = true,
    )
}
