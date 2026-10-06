package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.model.LocalModelRouteIdentity
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
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
                write = 15,
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
        assertEquals(15L, snapshot.tracked.cacheWriteTokens)
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
        write: Long = 0,
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
        cacheWriteTokens = write,
        outputTokens = output,
        reasoningTokens = reasoning,
        reported = true,
    )

    @Test
    fun duplicateRequestIdBeyondRecentWindowIsStillCountedOnce() {
        val records = buildList {
            add(
                record(
                    id = "replayed",
                    time = 1L,
                    input = 10L,
                    output = 5L,
                    context = TokenUsageContext(mode = LocalUsageMode.CHAT, turnId = "turn-original"),
                ),
            )
            repeat(1_100) { index ->
                add(
                    record(
                        id = "unique-$index",
                        time = index + 2L,
                        input = 1L,
                        output = 0L,
                        context = TokenUsageContext(mode = LocalUsageMode.CHAT, turnId = "turn-$index"),
                    ),
                )
            }
            add(
                record(
                    id = "replayed",
                    time = 2_000L,
                    input = 10_000L,
                    output = 10_000L,
                    context = TokenUsageContext(mode = LocalUsageMode.CHAT, turnId = "turn-replay"),
                ),
            )
        }

        val snapshot = aggregateTokenUsageRecords(records.asSequence(), ZoneId.of("UTC"))

        assertEquals(1_115L, snapshot.tracked.totalTokens)
        assertEquals(1_101L, snapshot.tracked.requestCount)
        assertEquals(200, snapshot.recentRecords.size)
    }

    @Test
    fun extremeTokenValuesSaturateInsteadOfWrappingNegative() {
        val records = sequenceOf(
            record(
                id = "max-a",
                time = 1L,
                input = Long.MAX_VALUE,
                output = Long.MAX_VALUE,
                context = TokenUsageContext(mode = LocalUsageMode.WORK, runId = "run"),
            ),
            record(
                id = "max-b",
                time = 2L,
                input = Long.MAX_VALUE,
                output = Long.MAX_VALUE,
                context = TokenUsageContext(mode = LocalUsageMode.WORK, runId = "run"),
            ),
        )

        val snapshot = aggregateTokenUsageRecords(records, ZoneId.of("UTC"))

        assertEquals(Long.MAX_VALUE, snapshot.tracked.inputTokens)
        assertEquals(Long.MAX_VALUE, snapshot.tracked.outputTokens)
        assertEquals(Long.MAX_VALUE, snapshot.tracked.totalTokens)
        assertTrue(snapshot.work.averageInputPerTurn >= 0L)
        assertTrue(snapshot.work.averageOutputPerTurn >= 0L)
    }

    @Test
    fun negativeOrNonFiniteAccountingDataCannotPoisonTotals() {
        val snapshot = aggregateTokenUsageRecords(
            sequenceOf(
                TokenUsageRecord(
                    requestId = "bad",
                    timestamp = 1L,
                    model = "test",
                    context = TokenUsageContext(mode = LocalUsageMode.CHAT, turnId = "turn"),
                    inputTokens = -5L,
                    cacheHitTokens = -2L,
                    cacheMissTokens = -3L,
                    cacheWriteTokens = -4L,
                    outputTokens = -7L,
                    reasoningTokens = -1L,
                    estimatedCostCny = Double.NaN,
                    reported = true,
                ),
            ),
            ZoneId.of("UTC"),
        )

        assertEquals(0L, snapshot.tracked.totalTokens)
        assertEquals(0L, snapshot.tracked.cacheMeasuredTokens)
        assertEquals(0L, snapshot.tracked.cacheWriteTokens)
        assertEquals(0.0, snapshot.tracked.estimatedCostCny, 0.0)
    }

    @Test
    fun promptBreakdownCannotOverflowBeforeConversionToLong() {
        val breakdown = TokenPromptBreakdown(
            systemBaseTokens = Int.MAX_VALUE,
            personaStateTokens = Int.MAX_VALUE,
            memoryRuleTokens = Int.MAX_VALUE,
            historyTokens = Int.MAX_VALUE,
            currentUserTokens = Int.MAX_VALUE,
            toolDefinitionTokens = Int.MAX_VALUE,
            otherSystemTokens = Int.MAX_VALUE,
        )

        assertEquals(Int.MAX_VALUE.toLong() * 7L, breakdown.estimatedInputTokens)
    }

    @Test
    fun routeIdentityIsBackwardCompatibleAndRoundTripsInUsageRecords() {
        val json = kotlinx.serialization.json.Json
        val legacy = json.decodeFromString(
            TokenUsageRecord.serializer(),
            """{"requestId":"legacy","timestamp":1,"model":"model"}""",
        )
        assertEquals(null, legacy.route)
        assertEquals(0L, legacy.cacheWriteTokens)

        val route = LocalModelRouteIdentity(
            profileId = "profile-a",
            provider = "DeepSeek",
            model = "deepseek-flash",
            baseUrl = "https://api.deepseek.com",
            authKind = LocalModelAuthKind.API_KEY.name,
            protocol = LocalModelProtocol.CHAT_COMPLETIONS.name,
            fingerprint = "route-fingerprint",
        )
        val source = TokenUsageRecord(
            requestId = "routed",
            timestamp = 2L,
            model = route.model,
            route = route,
            reported = true,
        )
        val decoded = json.decodeFromString(
            TokenUsageRecord.serializer(),
            json.encodeToString(TokenUsageRecord.serializer(), source),
        )
        assertEquals(route, decoded.route)
    }

}
