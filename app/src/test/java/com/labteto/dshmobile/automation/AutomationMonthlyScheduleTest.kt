package com.labteto.dshmobile.automation

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationMonthlyScheduleTest {
    private fun timestamp(year: Int, month: Int, day: Int, hour: Int = 9): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    private fun utc(block: () -> Unit) {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            block()
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun januaryAnchorRestoresDayAfterShortMonth() = utc {
        val anchor = timestamp(2025, 1, 31)
        assertEquals(timestamp(2025, 2, 28), nextMonthlyAnchoredRun(anchor, anchor))
        assertEquals(timestamp(2025, 3, 31), nextMonthlyAnchoredRun(anchor, timestamp(2025, 2, 28)))
        assertEquals(timestamp(2025, 4, 30), nextMonthlyAnchoredRun(anchor, timestamp(2025, 3, 31)))
    }

    @Test fun leapYearAndMissedMonthsRetainOriginalHour() = utc {
        val anchor = timestamp(2024, 1, 31, 16)
        assertEquals(timestamp(2024, 2, 29, 16), nextMonthlyAnchoredRun(anchor, anchor))
        assertEquals(timestamp(2026, 1, 31, 16), nextMonthlyAnchoredRun(anchor, timestamp(2026, 1, 1)))
    }

    @Test fun workAndChatBothUseCalendarChaining() {
        val task = AutomationTask(
            id = "monthly", prompt = "audit", createdAt = 1L, nextRunAt = 100L,
            recurringMinutes = 30L * 24L * 60L,
            scheduleType = AutomationScheduleType.MONTHLY,
            mode = AutomationMode.WORK,
        )
        assertTrue(usesChainedAutomationScheduling(task))
        assertTrue(usesChainedAutomationScheduling(task.copy(mode = AutomationMode.CHAT)))
    }
}
