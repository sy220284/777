package com.labteto.dshmobile.automation

import java.util.Calendar

/**
 * Overflow-safe arithmetic and anchored scheduling helpers for automation time calculations.
 *
 * Keep malformed or unrepresentable user-supplied time values from silently wrapping into the
 * past, which could otherwise turn a distant schedule into an immediate run.
 */
internal fun checkedAutomationMinutesToMillis(
    minutes: Long,
    label: String,
    allowZero: Boolean = true,
): Long {
    if (allowZero) {
        require(minutes >= 0L) { "$label不能为负数" }
    } else {
        require(minutes > 0L) { "$label必须大于 0" }
    }
    return try {
        Math.multiplyExact(minutes, 60_000L)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("$label过大")
    }
}

internal fun checkedAutomationAddMillis(
    baseMillis: Long,
    deltaMillis: Long,
    label: String,
): Long = try {
    Math.addExact(baseMillis, deltaMillis)
} catch (_: ArithmeticException) {
    throw IllegalArgumentException("$label超出可表示时间范围")
}

internal fun checkedAutomationFutureMillis(
    baseMillis: Long,
    minutes: Long,
    label: String,
): Long = checkedAutomationAddMillis(
    baseMillis = baseMillis,
    deltaMillis = checkedAutomationMinutesToMillis(minutes, label),
    label = label,
)

internal fun parseOptionalAutomationLong(raw: String?, label: String): Long? {
    if (raw == null) return null
    return raw.toLongOrNull() ?: throw IllegalArgumentException("$label必须是 64 位整数")
}

internal fun nextIntervalAnchoredRun(
    anchorMillis: Long,
    afterMillis: Long,
    intervalMinutes: Long,
): Long {
    val intervalMillis = checkedAutomationMinutesToMillis(
        intervalMinutes,
        label = "任务周期",
        allowZero = false,
    )
    if (anchorMillis > afterMillis) return anchorMillis
    val elapsed = try {
        Math.subtractExact(afterMillis, anchorMillis)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("任务时间跨度过大")
    }
    val steps = elapsed / intervalMillis + 1L
    return try {
        Math.addExact(anchorMillis, Math.multiplyExact(steps, intervalMillis))
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("下次任务时间超出可表示范围")
    }
}

internal fun nextAnchoredAutomationRun(
    task: AutomationTask,
    afterMillis: Long,
    suggestedRunAt: Long? = null,
): Long? {
    if (task.scheduleType == AutomationScheduleType.WINDOW) {
        val constrainedAfter = maxOf(
            afterMillis,
            suggestedRunAt?.let {
                checkedAutomationAddMillis(it, -60_000L, "建议执行时间")
            } ?: afterMillis,
        )
        return nextDailyWindowRun(
            previousScheduledAt = task.nextRunAt,
            afterMillis = constrainedAfter,
            startMinuteOfDay = task.windowStartMinuteOfDay ?: return null,
            endMinuteOfDay = task.windowEndMinuteOfDay ?: return null,
        )
    }

    suggestedRunAt?.takeIf { it > afterMillis }?.let { return it }

    if (task.scheduleType == AutomationScheduleType.SILENCE) {
        return task.silenceMinutes?.let {
            checkedAutomationFutureMillis(afterMillis, it, "沉默触发间隔")
        }
    }

    val anchor = task.scheduleAnchorAt ?: task.nextRunAt
    return when (task.scheduleType) {
        AutomationScheduleType.DAILY ->
            nextCalendarAnchoredRun(anchor, afterMillis, Calendar.DAY_OF_YEAR, 1)
        AutomationScheduleType.WEEKLY ->
            nextCalendarAnchoredRun(anchor, afterMillis, Calendar.WEEK_OF_YEAR, 1)
        AutomationScheduleType.INTERVAL -> {
            val minutes = task.recurringMinutes ?: return null
            nextIntervalAnchoredRun(anchor, afterMillis, minutes)
        }
        else -> task.recurringMinutes?.let {
            checkedAutomationFutureMillis(afterMillis, it, "任务周期")
        }
    }
}

private fun nextCalendarAnchoredRun(
    anchorMillis: Long,
    afterMillis: Long,
    field: Int,
    amount: Int,
): Long {
    val calendar = Calendar.getInstance().apply { timeInMillis = anchorMillis }
    while (calendar.timeInMillis <= afterMillis) {
        calendar.add(field, amount)
    }
    return calendar.timeInMillis
}
