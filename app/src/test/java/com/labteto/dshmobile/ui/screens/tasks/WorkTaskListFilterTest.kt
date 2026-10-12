package com.labteto.dshmobile.ui.screens.tasks

import com.labteto.dshmobile.automation.AutomationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkTaskListFilterTest {
    @Test fun everyStatusRemainsVisibleInAll() {
        AutomationStatus.entries.forEach { status ->
            assertTrue(workTaskMatchesFilter(status, WorkTaskListFilter.ALL))
        }
    }

    @Test fun tabsReflectRealScheduleStateOnly() {
        val expected = mapOf(
            WorkTaskListFilter.SCHEDULED to setOf(AutomationStatus.SCHEDULED, AutomationStatus.QUEUED),
            WorkTaskListFilter.RUNNING to setOf(AutomationStatus.RUNNING, AutomationStatus.WAITING_USER),
            WorkTaskListFilter.PAUSED to setOf(AutomationStatus.PAUSED),
        )
        expected.forEach { (filter, statuses) ->
            assertEquals(statuses, AutomationStatus.entries.filter {
                workTaskMatchesFilter(it, filter)
            }.toSet())
        }
    }
}
