package com.labteto.dshmobile.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationCommandScheduleTest {
    private val future = 10_000L
    private val now = 1_000L
    private fun valid(type: AutomationScheduleType, minutes: Long?, mode: AutomationMode = AutomationMode.WORK) =
        validAutomationCommandSchedule(mode, type, future, minutes, now)

    @Test fun customMissingPeriodCannotBecomeOneShot() {
        assertFalse(valid(AutomationScheduleType.INTERVAL, null))
        assertFalse(valid(AutomationScheduleType.INTERVAL, 0))
        assertFalse(valid(AutomationScheduleType.INTERVAL, 14))
        assertTrue(valid(AutomationScheduleType.INTERVAL, 15))
        assertFalse(valid(AutomationScheduleType.INTERVAL, Long.MAX_VALUE))
        assertFalse(valid(AutomationScheduleType.INTERVAL, 15, AutomationMode.CHAT))
        assertTrue(valid(AutomationScheduleType.INTERVAL, 60, AutomationMode.CHAT))
    }

    @Test fun typedCadenceNeverSilentlyChangesScheduleType() {
        assertTrue(valid(AutomationScheduleType.ONCE, null))
        assertFalse(valid(AutomationScheduleType.ONCE, 60))
        assertFalse(valid(AutomationScheduleType.LEGACY, null))
        assertTrue(valid(AutomationScheduleType.DAILY, 1440))
        assertFalse(valid(AutomationScheduleType.DAILY, 60))
        assertTrue(valid(AutomationScheduleType.WEEKLY, 10080))
        assertTrue(valid(AutomationScheduleType.MONTHLY, 43200))
        assertFalse(valid(AutomationScheduleType.MONTHLY, 44640))
        assertFalse(validAutomationCommandSchedule(AutomationMode.WORK,
            AutomationScheduleType.ONCE, 999, null, now))
    }

    @Test fun specialChatOnlySchedulesRequireCompleteFields() {
        assertFalse(validAutomationCommandSchedule(AutomationMode.WORK,
            AutomationScheduleType.SILENCE, 0, null, now, silenceMinutes = 60))
        assertTrue(validAutomationCommandSchedule(AutomationMode.CHAT,
            AutomationScheduleType.SILENCE, 0, null, now, silenceMinutes = 60))
        assertFalse(validAutomationCommandSchedule(AutomationMode.CHAT,
            AutomationScheduleType.WINDOW, 0, null, now, windowStartMinuteOfDay = 100))
        assertTrue(validAutomationCommandSchedule(AutomationMode.CHAT,
            AutomationScheduleType.WINDOW, 0, null, now,
            windowStartMinuteOfDay = 100, windowEndMinuteOfDay = 300))
    }

    @Test fun concurrentTaskIdsAreIndependentOfClock() {
        val ids = (1..1000).map { newAutomationTaskId() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("ui-") })
    }
}
