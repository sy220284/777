package com.labteto.dshmobile.automation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationReceiptTest {

    @Test
    fun persistedLegacyStatusStringsDecodeIntoTypedAutomationStatus() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val decoded = json.decodeFromString(
            AutomationTask.serializer(),
            """{"id":"legacy","prompt":"task","createdAt":1,"nextRunAt":2,"status":"waiting_user"}""",
        )

        assertEquals(AutomationStatus.WAITING_USER, decoded.status)
        assertTrue(
            json.encodeToString(AutomationTask.serializer(), decoded)
                .contains("\"status\":\"waiting_user\""),
        )
    }

    @Test
    fun unknownPersistedAutomationStatusDegradesToBlockedOnDowngrade() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val decoded = json.decodeFromString(
            AutomationTask.serializer(),
            """{"id":"future","prompt":"task","createdAt":1,"nextRunAt":2,"status":"future_waiting"}""",
        )

        assertEquals(AutomationStatus.BLOCKED, decoded.status)
        assertTrue(
            json.encodeToString(AutomationTask.serializer(), decoded)
                .contains("\"status\":\"blocked\""),
        )
    }

    @Test
    fun scheduleGenerationGetsDistinctWorkIdentityAndKeepsLegacyGenerationZero() {
        assertEquals("harness-automation-task", automationWorkName("task", 0L))
        assertEquals("harness-automation-task-g1", automationWorkName("task", 1L))
        assertEquals("harness-automation-task-g42", automationWorkName("task", 42L))
    }

    @Test
    fun automationTaskPersistsScheduleGenerationWithoutNormalizationLoss() {
        val task = AutomationTask(
            id = "generation",
            prompt = "task",
            createdAt = 0L,
            nextRunAt = 1L,
            scheduleGeneration = 7L,
        )

        assertEquals(7L, normalizeAutomationTask(task).scheduleGeneration)
    }

    @Test
    fun keepsNewestTwoHundredReceipts() {
        var history = emptyList<AutomationRunReceipt>()
        repeat(250) { index ->
            history = appendAutomationReceipt(
                history,
                AutomationRunReceipt(
                    startedAt = index.toLong(),
                    finishedAt = index.toLong(),
                    status = AutomationStatus.COMPLETED,
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
        val old = AutomationRunReceipt(0L, 0L, AutomationStatus.COMPLETED)
        val recent = AutomationRunReceipt(31L * day, 31L * day, AutomationStatus.FAILED)

        val history = appendAutomationReceipt(listOf(old), recent)

        assertEquals(1, history.size)
        assertTrue(history.single().status == AutomationStatus.FAILED)
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

        assertTrue(usesChainedAutomationScheduling(task))
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
    fun randomDailyWindowSchedulesInsideTodayWindow() {
        val calendar = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.SEPTEMBER, 27, 18, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val runAt = firstDailyWindowRun(
            afterMillis = calendar.timeInMillis,
            startMinuteOfDay = 20 * 60,
            endMinuteOfDay = 22 * 60,
            randomFraction = 0.5,
        )
        val result = java.util.Calendar.getInstance().apply { timeInMillis = runAt }

        assertEquals(21, result.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, result.get(java.util.Calendar.MINUTE))
    }

    @Test
    fun randomDailyWindowAdvancesToNextDayAfterRun() {
        val previous = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.SEPTEMBER, 27, 21, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val finished = previous.timeInMillis + 5L * 60_000L
        val next = nextDailyWindowRun(
            previousScheduledAt = previous.timeInMillis,
            afterMillis = finished,
            startMinuteOfDay = 20 * 60,
            endMinuteOfDay = 22 * 60,
            randomFraction = 0.5,
        )
        val result = java.util.Calendar.getInstance().apply { timeInMillis = next }

        assertEquals(28, result.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(21, result.get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test
    fun randomDailyWindowSupportsOvernightRange() {
        val previous = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.SEPTEMBER, 27, 23, 30, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val next = nextDailyWindowRun(
            previousScheduledAt = previous.timeInMillis,
            afterMillis = previous.timeInMillis + 10L * 60_000L,
            startMinuteOfDay = 23 * 60,
            endMinuteOfDay = 7 * 60,
            randomFraction = 0.5,
        )
        val result = java.util.Calendar.getInstance().apply { timeInMillis = next }

        assertEquals(29, result.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(3, result.get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test
    fun deferredRetryStaysInsideRandomWindow() {
        val previous = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.SEPTEMBER, 27, 21, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val suggested = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.SEPTEMBER, 28, 23, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val task = AutomationTask(
            id = "window",
            prompt = "来找我",
            createdAt = 0L,
            nextRunAt = previous.timeInMillis,
            recurringMinutes = 24L * 60L,
            scheduleType = AutomationScheduleType.WINDOW,
            windowStartMinuteOfDay = 20 * 60,
            windowEndMinuteOfDay = 22 * 60,
            mode = AutomationMode.CHAT,
            targetSessionId = "session",
        )

        val next = nextAnchoredAutomationRun(
            task = task,
            afterMillis = previous.timeInMillis + 5L * 60_000L,
            suggestedRunAt = suggested.timeInMillis,
        )!!
        val result = java.util.Calendar.getInstance().apply { timeInMillis = next }
        val minuteOfDay = result.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            result.get(java.util.Calendar.MINUTE)

        assertEquals(29, result.get(java.util.Calendar.DAY_OF_MONTH))
        assertTrue(minuteOfDay in 20 * 60 until 22 * 60)
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
        val atCutoff = AutomationRunReceipt(day, day, AutomationStatus.COMPLETED)
        val recent = AutomationRunReceipt(31L * day, 31L * day, AutomationStatus.FAILED)

        val history = appendAutomationReceipt(listOf(atCutoff), recent)

        assertEquals(2, history.size)
        assertEquals(day, history.first().finishedAt)
    }
    @Test
    fun normalizesCorruptPersistedChatPolicyValues() {
        val restored = normalizeAutomationTask(
            AutomationTask(
                id = "corrupt-chat",
                prompt = "来找我",
                createdAt = 0L,
                nextRunAt = 1L,
                recurringMinutes = -5L,
                scheduleType = AutomationScheduleType.SILENCE,
                silenceMinutes = 0L,
                windowStartMinuteOfDay = -20,
                windowEndMinuteOfDay = 2_000,
                mode = AutomationMode.CHAT,
                targetSessionId = "session",
                quietStartHour = -1,
                quietStartMinute = 99,
                quietEndHour = 88,
                quietEndMinute = -4,
                proactiveMinGapMinutes = -1L,
                proactiveMaxUnanswered = 0,
                failureStreak = -3,
            ),
        )

        assertEquals(60L, restored.recurringMinutes)
        assertEquals(60L, restored.silenceMinutes)
        assertEquals(0, restored.windowStartMinuteOfDay)
        assertEquals(1_439, restored.windowEndMinuteOfDay)
        assertEquals(0, restored.quietStartHour)
        assertEquals(59, restored.quietStartMinute)
        assertEquals(23, restored.quietEndHour)
        assertEquals(0, restored.quietEndMinute)
        assertEquals(60L, restored.proactiveMinGapMinutes)
        assertEquals(1, restored.proactiveMaxUnanswered)
        assertEquals(0, restored.failureStreak)
    }

    @Test
    fun separatesEqualRestoredWindowBounds() {
        val restored = normalizeAutomationTask(
            AutomationTask(
                id = "window",
                prompt = "来找我",
                createdAt = 0L,
                nextRunAt = 1L,
                recurringMinutes = 24L * 60L,
                scheduleType = AutomationScheduleType.WINDOW,
                windowStartMinuteOfDay = 20 * 60,
                windowEndMinuteOfDay = 20 * 60,
                mode = AutomationMode.CHAT,
                targetSessionId = "session",
            ),
        )

        assertEquals(20 * 60, restored.windowStartMinuteOfDay)
        assertEquals(22 * 60, restored.windowEndMinuteOfDay)
    }

    @Test
    fun restoresMissingSilenceAndWindowFields() {
        val silence = normalizeAutomationTask(
            AutomationTask(
                id = "silence-missing",
                prompt = "来找我",
                createdAt = 0L,
                nextRunAt = 1L,
                scheduleType = AutomationScheduleType.SILENCE,
                mode = AutomationMode.CHAT,
                targetSessionId = "session",
            ),
        )
        val window = normalizeAutomationTask(
            AutomationTask(
                id = "window-missing",
                prompt = "来找我",
                createdAt = 0L,
                nextRunAt = 1L,
                scheduleType = AutomationScheduleType.WINDOW,
                mode = AutomationMode.CHAT,
                targetSessionId = "session",
            ),
        )

        assertEquals(60L, silence.silenceMinutes)
        assertEquals(60L, silence.recurringMinutes)
        assertEquals(20 * 60, window.windowStartMinuteOfDay)
        assertEquals(22 * 60, window.windowEndMinuteOfDay)
    }


    @Test
    fun hugeAutomationMinutesAreRejectedInsteadOfOverflowing() {
        val failure = runCatching {
            checkedAutomationMinutesToMillis(Long.MAX_VALUE, "任务延迟")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun negativeAutomationDelayIsRejectedInsteadOfBecomingImmediate() {
        val failure = runCatching {
            checkedAutomationFutureMillis(1_000L, -1L, "任务延迟")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun futureTimestampOverflowIsRejectedInsteadOfWrappingIntoPast() {
        val failure = runCatching {
            checkedAutomationFutureMillis(Long.MAX_VALUE - 30_000L, 1L, "任务延迟")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun anchoredIntervalRejectsUnrepresentableNextRun() {
        val failure = runCatching {
            nextIntervalAnchoredRun(
                anchorMillis = Long.MAX_VALUE - 10_000L,
                afterMillis = Long.MAX_VALUE - 1L,
                intervalMinutes = 1L,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }


    @Test
    fun malformedAutomationIntegerIsRejectedInsteadOfDefaultingToZero() {
        val failure = runCatching {
            parseOptionalAutomationLong("999999999999999999999999999", "delay_minutes")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun missingAutomationIntegerRemainsOptional() {
        assertEquals(null, parseOptionalAutomationLong(null, "delay_minutes"))
    }

    @Test
    fun checkedAutomationAddRejectsWindowOverflow() {
        val failure = runCatching {
            checkedAutomationAddMillis(Long.MAX_VALUE - 10L, 60_000L, "时间窗起点")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }


    @Test
    fun dailyAnchorJumpsAcrossDecadesWithoutIteratingEveryDay() {
        val anchor = java.util.Calendar.getInstance().apply {
            set(2000, java.util.Calendar.JANUARY, 1, 9, 30, 15)
            set(java.util.Calendar.MILLISECOND, 123)
        }
        val after = java.util.Calendar.getInstance().apply {
            set(2099, java.util.Calendar.DECEMBER, 31, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val task = AutomationTask(
            id = "daily-decades",
            prompt = "task",
            createdAt = anchor.timeInMillis,
            nextRunAt = anchor.timeInMillis,
            recurringMinutes = 24L * 60L,
            scheduleType = AutomationScheduleType.DAILY,
            scheduleAnchorAt = anchor.timeInMillis,
        )

        val next = requireNotNull(nextAnchoredAutomationRun(task, after.timeInMillis))
        val result = java.util.Calendar.getInstance().apply { timeInMillis = next }

        assertTrue(next > after.timeInMillis)
        assertEquals(9, result.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(30, result.get(java.util.Calendar.MINUTE))
        assertEquals(15, result.get(java.util.Calendar.SECOND))
        assertEquals(123, result.get(java.util.Calendar.MILLISECOND))
        assertEquals(1, result.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(java.util.Calendar.JANUARY, result.get(java.util.Calendar.MONTH))
        assertEquals(2100, result.get(java.util.Calendar.YEAR))
    }

    @Test
    fun weeklyAnchorJumpsAcrossDecadesToSameWeekdayAndTime() {
        val anchor = java.util.Calendar.getInstance().apply {
            set(2000, java.util.Calendar.JANUARY, 3, 9, 30, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val after = java.util.Calendar.getInstance().apply {
            set(2099, java.util.Calendar.DECEMBER, 31, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val task = AutomationTask(
            id = "weekly-decades",
            prompt = "task",
            createdAt = anchor.timeInMillis,
            nextRunAt = anchor.timeInMillis,
            recurringMinutes = 7L * 24L * 60L,
            scheduleType = AutomationScheduleType.WEEKLY,
            scheduleAnchorAt = anchor.timeInMillis,
        )

        val next = requireNotNull(nextAnchoredAutomationRun(task, after.timeInMillis))
        val result = java.util.Calendar.getInstance().apply { timeInMillis = next }

        assertTrue(next > after.timeInMillis)
        assertEquals(anchor.get(java.util.Calendar.DAY_OF_WEEK), result.get(java.util.Calendar.DAY_OF_WEEK))
        assertEquals(anchor.get(java.util.Calendar.HOUR_OF_DAY), result.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(anchor.get(java.util.Calendar.MINUTE), result.get(java.util.Calendar.MINUTE))
        assertTrue(next - after.timeInMillis <= 8L * 24L * 60L * 60L * 1000L)
    }


    @Test
    fun failureCounterSaturatesInsteadOfWrappingNegative() {
        assertEquals(1, 0.saturatingIncrement())
        assertEquals(Int.MAX_VALUE, (Int.MAX_VALUE - 1).saturatingIncrement())
        assertEquals(Int.MAX_VALUE, Int.MAX_VALUE.saturatingIncrement())
        assertEquals(0, Int.MIN_VALUE.saturatingIncrement())
    }

}
