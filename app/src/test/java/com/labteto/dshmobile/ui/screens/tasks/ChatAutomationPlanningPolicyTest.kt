package com.labteto.dshmobile.ui.screens.tasks

import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.automation.AutomationStatus
import com.labteto.dshmobile.local.automation.parseAutomationPlan
import com.labteto.dshmobile.local.automation.parseAutomationSuggestions
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAutomationPlanningPolicyTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun plannerClampsOneTimeEventIntoFuture() {
        val now = 1_000_000L
        val draft = parseAutomationPlan(
            raw = """{"task":{"prompt":"晚上来聊今天发生的事","scheduleType":"ONCE","firstRunAtMillis":10}}""",
            json = json,
            nowMillis = now,
            sourceSessionId = "session-a",
        )

        assertEquals("session-a", draft.sourceSessionId)
        assertEquals(AutomationScheduleType.ONCE, draft.scheduleType)
        assertEquals(now + 60_000L, draft.firstRunAt)
        assertEquals("晚上来聊今天发生的事", draft.prompt)
    }

    @Test
    fun plannerNormalizesRecurringAndSilenceLimits() {
        val now = 5_000_000L
        val interval = parseAutomationPlan(
            raw = """{"task":{"prompt":"隔一阵来看看","scheduleType":"INTERVAL","firstRunAtMillis":6000000,"recurringMinutes":15}}""",
            json = json,
            nowMillis = now,
            sourceSessionId = "session-a",
        )
        val silence = parseAutomationPlan(
            raw = """{"task":{"prompt":"很久没说话时来问问","scheduleType":"SILENCE","silenceMinutes":20}}""",
            json = json,
            nowMillis = now,
            sourceSessionId = "session-a",
        )

        assertEquals(60L, interval.recurringMinutes)
        assertEquals(60L, silence.silenceMinutes)
    }

    @Test
    fun suggestionsAreDeduplicatedAndLimitedToThree() {
        val suggestions = parseAutomationSuggestions(
            """{"suggestions":["明晚来聊今天的事","周末问问计划","周末问问计划","三天没聊时来找我"]}""",
            json,
        )

        assertEquals(
            listOf("明晚来聊今天的事", "周末问问计划", "三天没聊时来找我"),
            suggestions,
        )
    }

    @Test
    fun visualStatusDistinguishesPendingOngoingAndCompleted() {
        val pending = task(status = AutomationStatus.SCHEDULED, recurringMinutes = 24L * 60L)
        val ongoing = pending.copy(lastRunAt = 10L)
        val completed = task(status = AutomationStatus.COMPLETED)

        assertEquals(ChatAutomationVisualStatus.PENDING, chatAutomationVisualStatus(pending))
        assertEquals(ChatAutomationVisualStatus.ONGOING, chatAutomationVisualStatus(ongoing))
        assertEquals(ChatAutomationVisualStatus.COMPLETED, chatAutomationVisualStatus(completed))
        assertTrue(sortChatAutomationTasks(listOf(completed, pending)).first().id == pending.id)
    }

    private fun task(
        status: AutomationStatus,
        recurringMinutes: Long? = null,
    ) = AutomationTask(
        id = "task-${status.wireValue}-${recurringMinutes ?: 0}",
        prompt = "测试事件",
        createdAt = 1L,
        nextRunAt = 2L,
        recurringMinutes = recurringMinutes,
        scheduleType = if (recurringMinutes == null) {
            AutomationScheduleType.ONCE
        } else {
            AutomationScheduleType.DAILY
        },
        mode = AutomationMode.CHAT,
        targetSessionId = "session-a",
        status = status,
    )
}
