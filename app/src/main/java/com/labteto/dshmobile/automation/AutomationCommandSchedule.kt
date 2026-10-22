package com.labteto.dshmobile.automation

import java.util.UUID

/** A requested recurring schedule must never silently turn into a one-shot schedule. */
internal fun validAutomationCommandSchedule(
    mode: AutomationMode,
    type: AutomationScheduleType,
    firstRunAt: Long,
    recurringMinutes: Long?,
    now: Long,
    silenceMinutes: Long? = null,
    windowStartMinuteOfDay: Int? = null,
    windowEndMinuteOfDay: Int? = null,
): Boolean {
    if (type !in setOf(AutomationScheduleType.SILENCE, AutomationScheduleType.WINDOW) &&
        firstRunAt <= now) return false
    val minimum = if (mode == AutomationMode.CHAT) 60L else 15L
    fun recurring(value: Long?): Boolean = value != null && value >= minimum &&
        runCatching { checkedAutomationMinutesToMillis(value, "任务周期", allowZero = false) }.isSuccess
    return when (type) {
        AutomationScheduleType.ONCE -> recurringMinutes == null
        AutomationScheduleType.INTERVAL, AutomationScheduleType.LEGACY -> recurring(recurringMinutes)
        AutomationScheduleType.DAILY -> recurringMinutes == 24L * 60L
        AutomationScheduleType.WEEKLY -> recurringMinutes == 7L * 24L * 60L
        AutomationScheduleType.MONTHLY -> recurringMinutes == 30L * 24L * 60L
        AutomationScheduleType.SILENCE -> mode == AutomationMode.CHAT &&
            silenceMinutes != null && silenceMinutes >= 60L &&
            runCatching { checkedAutomationMinutesToMillis(silenceMinutes, "沉默触发", allowZero = false) }.isSuccess
        AutomationScheduleType.WINDOW -> mode == AutomationMode.CHAT &&
            windowStartMinuteOfDay != null && windowEndMinuteOfDay != null &&
            windowStartMinuteOfDay in 0 until 24 * 60 &&
            windowEndMinuteOfDay in 0 until 24 * 60 &&
            windowStartMinuteOfDay != windowEndMinuteOfDay
    }
}

/** UUID identity survives two concurrent task creates in the same millisecond. */
internal fun newAutomationTaskId(): String = "ui-${UUID.randomUUID()}"
