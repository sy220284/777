package com.labteto.dshmobile.local.automation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationPlanningContentTest {
    @Test
    fun longUserScheduleInstructionsReachModelWithoutSilentTwoThousandCharacterCutoff() {
        val instructions = "请在指定时刻回顾全部人物经历与对话要求。".repeat(180)
        assertEquals(instructions, automationPlanningUserRequest("@角色事件规划 " + instructions))
    }

    @Test
    fun parsedLongAutomationPromptRemainsCompleteForExecutionAndRestart() {
        val prompt = "根据当时的真实聊天上下文继续执行人物约定，不增加新事件。".repeat(180)
        val raw = buildJsonObject {
            put("task", buildJsonObject {
                put("prompt", prompt)
                put("scheduleType", "ONCE")
                put("firstRunAtMillis", 1_700_000_500_000L)
            })
        }.toString()
        val draft = parseAutomationPlan(
            raw, Json, nowMillis = 1_700_000_000_000L, sourceSessionId = "long-task",
        )
        assertEquals(prompt, draft.prompt)
    }

    @Test
    fun missingOrPastFirstExecutionTimeDoesNotCreateAnUnintendedImmediateEvent() {
        fun parse(type: String, firstRun: Long?): Result<AutomationPlanDraft> =
            runCatching {
                parseAutomationPlan(buildJsonObject {
                    put("prompt", "仅按约定的时间提醒")
                    put("scheduleType", type)
                    if (firstRun != null) put("firstRunAtMillis", firstRun)
                }.toString(), Json, nowMillis = 1_700_000_000_000L, sourceSessionId = "test")
            }
        for (kind in listOf("ONCE", "DAILY", "WEEKLY", "MONTHLY", "INTERVAL")) {
            assertEquals(true, parse(kind, null).isFailure)
            assertEquals(true, parse(kind, 1_699_999_999_999L).isFailure)
        }
        assertEquals(true, parse("ONCE", 1_700_000_000_001L).isFailure)
        assertEquals(1_700_000_500_000L, parse("ONCE", 1_700_000_500_000L).getOrThrow().firstRunAt)
    }

    @Test
    fun missingIntervalsAndOverflowedTimeWindowsAreRejectedRatherThanCoerced() {
        fun parse(type: String, data: kotlinx.serialization.json.JsonObject): Result<AutomationPlanDraft> =
            runCatching {
                parseAutomationPlan(buildJsonObject {
                    put("prompt", "只使用指定参数")
                    put("scheduleType", type)
                    for ((key, value) in data) put(key, value)
                }.toString(), Json, nowMillis = 1_700_000_000_000L, sourceSessionId = "test")
            }
        val future = buildJsonObject { put("firstRunAtMillis", 1_700_000_500_000L) }
        assertEquals(true, parse("INTERVAL", future).isFailure)
        assertEquals(true, parse("INTERVAL", buildJsonObject {
            put("firstRunAtMillis", 1_700_000_500_000L)
            put("recurringMinutes", 59L)
        }).isFailure)
        assertEquals(true, parse("SILENCE", buildJsonObject {}).isFailure)
        assertEquals(true, parse("SILENCE", buildJsonObject { put("silenceMinutes", 59L) }).isFailure)
        assertEquals(true, parse("WINDOW", buildJsonObject {
            put("windowStartMinuteOfDay", 4_294_967_300L)
            put("windowEndMinuteOfDay", 120L)
        }).isFailure)
        val ok = parse("WINDOW", buildJsonObject {
            put("windowStartMinuteOfDay", 540L)
            put("windowEndMinuteOfDay", 600L)
        }).getOrThrow()
        assertEquals(540, ok.windowStartMinuteOfDay)
        assertEquals(600, ok.windowEndMinuteOfDay)
    }
}
