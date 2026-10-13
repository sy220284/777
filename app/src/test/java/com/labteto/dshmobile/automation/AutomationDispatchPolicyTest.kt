package com.labteto.dshmobile.automation

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationDispatchPolicyTest {
    private fun task(type: AutomationScheduleType, mode: AutomationMode) = AutomationTask(
        id = "cadence", prompt = "run", createdAt = 1L, nextRunAt = 100L,
        recurringMinutes = when (type) {
            AutomationScheduleType.WEEKLY -> 7L * 24L * 60L
            AutomationScheduleType.MONTHLY -> 30L * 24L * 60L
            AutomationScheduleType.ONCE -> null
            else -> 24L * 60L
        },
        scheduleType = type, mode = mode,
    )

    @Test fun newTypedTasksUseOneScheduleStrategyForBothModes() {
        for (type in listOf(
            AutomationScheduleType.INTERVAL,
            AutomationScheduleType.DAILY,
            AutomationScheduleType.WEEKLY,
            AutomationScheduleType.MONTHLY,
        )) {
            for (mode in AutomationMode.entries) {
                val current = task(type, mode)
                val policy = automationDispatchPolicy(type, current.recurringMinutes)
                assertEquals(AutomationDispatchPolicy.CHAINED, policy)
                assertTrue(usesChainedAutomationScheduling(current.copy(dispatchPolicy = policy)))
            }
        }
    }

    @Test fun oldWorkManagerRequestsAreNotSilentlyMigratedDuringStartup() {
        val oldWork = task(AutomationScheduleType.DAILY, AutomationMode.WORK)
        val oldChat = task(AutomationScheduleType.DAILY, AutomationMode.CHAT)
        assertFalse(usesChainedAutomationScheduling(oldWork))
        assertTrue(usesChainedAutomationScheduling(oldChat))
        assertEquals(AutomationDispatchPolicy.PERIODIC,
            automationDispatchPolicy(AutomationScheduleType.LEGACY, 60L))
        assertEquals(null, automationDispatchPolicy(AutomationScheduleType.ONCE, null))
        assertTrue(usesChainedAutomationScheduling(oldWork.copy(
            dispatchPolicy = automationDispatchPolicy(oldWork.scheduleType, oldWork.recurringMinutes),
        )))
    }

    @Test fun anchoredDailyTimeSurvivesDaylightSavingForBothModes() {
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            fun instant(day: Int): Long = Calendar.getInstance().apply {
                clear()
                set(2026, Calendar.MARCH, day, 9, 15, 0)
            }.timeInMillis
            val anchor = instant(7)
            val after = anchor + 1L
            for (mode in AutomationMode.entries) {
                val current = task(AutomationScheduleType.DAILY, mode).copy(
                    nextRunAt = anchor,
                    scheduleAnchorAt = anchor,
                    dispatchPolicy = AutomationDispatchPolicy.CHAINED,
                )
                assertTrue(usesChainedAutomationScheduling(current))
                assertEquals(instant(8), nextAnchoredAutomationRun(current, after))
            }
            assertEquals(23L * 60L * 60L * 1000L, instant(8) - instant(7))
        } finally {
            TimeZone.setDefault(zone)
        }
    }
}
