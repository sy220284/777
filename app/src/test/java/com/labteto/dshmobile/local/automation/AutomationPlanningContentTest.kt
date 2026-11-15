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
}
