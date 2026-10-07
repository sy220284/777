package com.labteto.dshmobile.automation

internal fun nextAutomationRunAfterUserActivity(
    task: AutomationTask,
    userMessageAt: Long,
): Long = when {
    task.scheduleType == AutomationScheduleType.SILENCE ->
        checkedAutomationFutureMillis(
            userMessageAt,
            requireNotNull(task.silenceMinutes),
            "沉默触发间隔",
        )
    task.scheduleType == AutomationScheduleType.INTERVAL ->
        checkedAutomationFutureMillis(
            userMessageAt,
            requireNotNull(task.recurringMinutes),
            "任务周期",
        )
    usesChainedAutomationScheduling(task) ->
        nextAnchoredAutomationRun(task, userMessageAt) ?: userMessageAt
    task.recurringMinutes != null ->
        maxOf(
            task.nextRunAt,
            checkedAutomationFutureMillis(
                userMessageAt,
                task.recurringMinutes,
                "任务周期",
            ),
        )
    else -> userMessageAt
}
