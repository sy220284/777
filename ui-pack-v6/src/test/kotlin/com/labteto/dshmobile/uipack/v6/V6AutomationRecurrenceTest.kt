package com.labteto.dshmobile.uipack.v6

import com.labteto.dshmobile.automation.AutomationScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V6AutomationRecurrenceTest {
    @Test fun calendarCadencesAlwaysProvideRecurrenceMetadata() {
        assertEquals(1440L, v6AutomationRecurrence(AutomationScheduleType.DAILY, null))
        assertEquals(10080L, v6AutomationRecurrence(AutomationScheduleType.WEEKLY, null))
        assertEquals(43200L, v6AutomationRecurrence(AutomationScheduleType.MONTHLY, null))
    }

    @Test fun intervalMustMeetExistingSchedulerMinimum() {
        assertNull(v6AutomationRecurrence(AutomationScheduleType.INTERVAL, 59L))
        assertEquals(60L, v6AutomationRecurrence(AutomationScheduleType.INTERVAL, 60L))
        assertEquals(1440L, v6AutomationRecurrence(AutomationScheduleType.INTERVAL, 1440L))
    }

    @Test fun oneTimeAndActivityTriggeredModesDoNotInventPeriodicAnchors() {
        assertNull(v6AutomationRecurrence(AutomationScheduleType.ONCE, null))
        assertNull(v6AutomationRecurrence(AutomationScheduleType.SILENCE, 90L))
        assertNull(v6AutomationRecurrence(AutomationScheduleType.WINDOW, 90L))
    }
}
