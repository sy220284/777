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
        require(minutes >= 0L) { "${label}不能为负数" }
    } else {
        require(minutes > 0L) { "${label}必须大于 0" }
    }
    return try {
        Math.multiplyExact(minutes, 60_000L)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("${label}过大")
    }
}

internal fun checkedAutomationAddMillis(
    baseMillis: Long,
    deltaMillis: Long,
    label: String,
): Long = try {
    Math.addExact(baseMillis, deltaMillis)
} catch (_: ArithmeticException) {
    throw IllegalArgumentException("${label}超出可表示时间范围")
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
    return raw.toLongOrNull() ?: throw IllegalArgumentException("${label}必须是 64 位整数")
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
        AutomationScheduleType.MONTHLY -> nextMonthlyAnchoredRun(anchor, afterMillis)
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
    require(amount == 1) { "日历锚定步长仅支持 1" }
    if (anchorMillis > afterMillis) return anchorMillis

    val anchor = Calendar.getInstance().apply { timeInMillis = anchorMillis }
    val candidate = Calendar.getInstance().apply {
        timeInMillis = afterMillis
        set(Calendar.HOUR_OF_DAY, anchor.get(Calendar.HOUR_OF_DAY))
        set(Calendar.MINUTE, anchor.get(Calendar.MINUTE))
        set(Calendar.SECOND, anchor.get(Calendar.SECOND))
        set(Calendar.MILLISECOND, anchor.get(Calendar.MILLISECOND))
    }

    when (field) {
        Calendar.DAY_OF_YEAR -> {
            if (candidate.timeInMillis <= afterMillis) {
                candidate.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        Calendar.WEEK_OF_YEAR -> {
            val targetDay = anchor.get(Calendar.DAY_OF_WEEK)
            val currentDay = candidate.get(Calendar.DAY_OF_WEEK)
            val deltaDays = Math.floorMod(targetDay - currentDay, 7)
            if (deltaDays != 0) candidate.add(Calendar.DAY_OF_YEAR, deltaDays)
            if (candidate.timeInMillis <= afterMillis) {
                candidate.add(Calendar.DAY_OF_YEAR, 7)
            }
        }
        else -> error("不支持的日历锚定字段：$field")
    }
    return candidate.timeInMillis
}

// Preserve the original calendar day across short months and leap years.
internal fun nextMonthlyAnchoredRun(anchorMillis: Long, afterMillis: Long): Long {
    if (anchorMillis > afterMillis) return anchorMillis
    val anchor = Calendar.getInstance().apply { timeInMillis = anchorMillis }
    val reference = Calendar.getInstance().apply { timeInMillis = afterMillis }
    val candidate = (anchor.clone() as Calendar).apply {
        set(Calendar.YEAR, reference.get(Calendar.YEAR))
        set(Calendar.MONTH, reference.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.DAY_OF_MONTH, minOf(anchor.get(Calendar.DAY_OF_MONTH), getActualMaximum(Calendar.DAY_OF_MONTH)))
    }
    if (candidate.timeInMillis <= afterMillis) {
        candidate.set(Calendar.DAY_OF_MONTH, 1)
        candidate.add(Calendar.MONTH, 1)
        candidate.set(Calendar.DAY_OF_MONTH, minOf(anchor.get(Calendar.DAY_OF_MONTH), candidate.getActualMaximum(Calendar.DAY_OF_MONTH)))
    }
    return candidate.timeInMillis
}
