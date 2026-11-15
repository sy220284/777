package com.labteto.dshmobile.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationToolContractTest {
    @Test
    fun ordinaryWorkAndBackgroundLanguageDoesNotPreselectSchedulers() {
        val names = listOf(
            "schedule_task", "schedule_recurring_task",
            "scheduled_task_list", "cancel_scheduled_task",
        )
        for (name in names) {
            val terms = automationToolMetadata(name).discoveryKeywords
            assertFalse("普通任务应留在 Work 主链：$name", "任务" in terms)
            assertFalse("后台执行不等同于创建定时任务：$name", "后台" in terms)
        }
    }

    @Test
    fun eachExplicitAutomationActionHasItsOwnDiscoveryTerms() {
        assertTrue("定时任务" in automationToolMetadata("schedule_task").discoveryKeywords)
        assertTrue("周期任务" in automationToolMetadata("schedule_recurring_task").discoveryKeywords)
        assertTrue("查看定时任务" in automationToolMetadata("scheduled_task_list").discoveryKeywords)
        assertTrue("取消定时" in automationToolMetadata("cancel_scheduled_task").discoveryKeywords)
        assertEquals(setOf("未知工具"), automationToolMetadata("未知工具").discoveryKeywords)
    }
}
