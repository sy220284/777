package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationStatus
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxAutomationScheduleLabel
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxAutomationStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Presentation labels must never be confused with persisted wire protocol values. */
class ZhaoxiAutomationDisplayTest {
    @Test fun everyRealOwnerStateHasReadableLocalizedLabel() {
        val statuses = AutomationStatus.entries
        assertEquals(10, statuses.size)
        statuses.forEach { status ->
            assertFalse(status.zxAutomationStatusLabel().isBlank())
            assertFalse(status.zxAutomationStatusLabel() == status.wireValue)
        }
        assertEquals("等待处理", AutomationStatus.WAITING_USER.zxAutomationStatusLabel())
        assertEquals("已受阻", AutomationStatus.BLOCKED.zxAutomationStatusLabel())
        assertEquals("执行失败", AutomationStatus.FAILED.zxAutomationStatusLabel())
        assertEquals("已完成", AutomationStatus.COMPLETED.zxAutomationStatusLabel())
    }

    @Test fun schedulesMirrorOwnerTypesWithoutGuessingCalendarIntervals() {
        assertEquals(8, AutomationScheduleType.entries.size)
        assertEquals("每月", AutomationScheduleType.MONTHLY.zxAutomationScheduleLabel())
        assertEquals("指定时段", AutomationScheduleType.WINDOW.zxAutomationScheduleLabel())
        AutomationScheduleType.entries.forEach {
            assertFalse(it.zxAutomationScheduleLabel().isBlank())
        }
    }
}
