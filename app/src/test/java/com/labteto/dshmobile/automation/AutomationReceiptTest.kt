package com.labteto.dshmobile.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationReceiptTest {
    @Test
    fun keepsNewestTwoHundredReceipts() {
        var history = emptyList<AutomationRunReceipt>()
        repeat(250) { index ->
            history = appendAutomationReceipt(
                history,
                AutomationRunReceipt(
                    startedAt = index.toLong(),
                    finishedAt = index.toLong(),
                    status = "completed",
                ),
            )
        }

        assertEquals(200, history.size)
        assertEquals(50L, history.first().finishedAt)
        assertEquals(249L, history.last().finishedAt)
    }

    @Test
    fun prunesReceiptsOutsideThirtyDayWindow() {
        val day = 24L * 60L * 60L * 1000L
        val old = AutomationRunReceipt(0L, 0L, "completed")
        val recent = AutomationRunReceipt(31L * day, 31L * day, "failed")

        val history = appendAutomationReceipt(listOf(old), recent)

        assertEquals(1, history.size)
        assertTrue(history.single().status == "failed")
    }

    @Test
    fun chatRecurringTaskAutoPausesAfterThirdFailure() {
        val task = AutomationTask(
            id = "chat",
            prompt = "主动来找我",
            createdAt = 0L,
            nextRunAt = 1L,
            recurringMinutes = 60L,
            mode = AutomationMode.CHAT,
            targetSessionId = "session",
        )

        assertFalse(shouldAutoPauseChatAutomation(task, 1))
        assertFalse(shouldAutoPauseChatAutomation(task, 2))
        assertTrue(shouldAutoPauseChatAutomation(task, 3))
    }

    @Test
    fun workAndOneShotTasksDoNotUseChatAutoPausePolicy() {
        val work = AutomationTask(
            id = "work",
            prompt = "task",
            createdAt = 0L,
            nextRunAt = 1L,
            recurringMinutes = 60L,
        )
        val oneShotChat = AutomationTask(
            id = "chat-once",
            prompt = "主动来找我",
            createdAt = 0L,
            nextRunAt = 1L,
            mode = AutomationMode.CHAT,
            targetSessionId = "session",
        )

        assertFalse(shouldAutoPauseChatAutomation(work, 3))
        assertFalse(shouldAutoPauseChatAutomation(oneShotChat, 3))
    }

    @Test
    fun anchoredIntervalDoesNotDriftFromCompletionTime() {
        val minute = 60_000L
        val anchor = 100L * minute

        val next = nextIntervalAnchoredRun(
            anchorMillis = anchor,
            afterMillis = anchor + 67L * minute,
            intervalMinutes = 60L,
        )

        assertEquals(anchor + 120L * minute, next)
    }

    @Test
    fun silenceSchedulePrefersSuggestedUserActivityDeadline() {
        val task = AutomationTask(
            id = "silence",
            prompt = "来找我",
            createdAt = 0L,
            nextRunAt = 1L,
            recurringMinutes = 360L,
            scheduleType = AutomationScheduleType.SILENCE,
            scheduleAnchorAt = 0L,
            silenceMinutes = 360L,
            mode = AutomationMode.CHAT,
            targetSessionId = "session",
        )

        assertTrue(usesChainedChatScheduling(task))
        assertEquals(
            99_000L,
            nextAnchoredAutomationRun(
                task = task,
                afterMillis = 10_000L,
                suggestedRunAt = 99_000L,
            ),
        )
    }

    @Test
    fun chatTaskKeepsProactivePolicyDefaults() {
        val task = AutomationTask(
            id = "chat-policy",
            prompt = "来找我",
            createdAt = 0L,
            nextRunAt = 1L,
            recurringMinutes = 360L,
            scheduleType = AutomationScheduleType.INTERVAL,
            mode = AutomationMode.CHAT,
            targetSessionId = "session",
        )

        assertEquals(23, task.quietStartHour)
        assertEquals(0, task.quietStartMinute)
        assertEquals(7, task.quietEndHour)
        assertEquals(0, task.quietEndMinute)
        assertEquals(360L, task.proactiveMinGapMinutes)
        assertEquals(2, task.proactiveMaxUnanswered)
    }

    @Test
    fun keepsReceiptExactlyAtThirtyDayCutoff() {
        val day = 24L * 60L * 60L * 1000L
        val atCutoff = AutomationRunReceipt(day, day, "completed")
        val recent = AutomationRunReceipt(31L * day, 31L * day, "failed")

        val history = appendAutomationReceipt(listOf(atCutoff), recent)

        assertEquals(2, history.size)
        assertEquals(day, history.first().finishedAt)
    }
}
