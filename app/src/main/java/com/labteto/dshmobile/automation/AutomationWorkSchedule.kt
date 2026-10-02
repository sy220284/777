package com.labteto.dshmobile.automation

import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal fun usesChainedChatScheduling(task: AutomationTask): Boolean =
    task.mode == AutomationMode.CHAT &&
        task.scheduleType in setOf(
            AutomationScheduleType.INTERVAL,
            AutomationScheduleType.DAILY,
            AutomationScheduleType.WEEKLY,
            AutomationScheduleType.SILENCE,
            AutomationScheduleType.WINDOW,
        )

internal fun automationWorkName(id: String, generation: Long): String =
    if (generation == 0L) "harness-automation-$id" else "harness-automation-$id-g$generation"

internal fun enqueueAutomationOneTime(
    workManager: WorkManager,
    id: String,
    generation: Long,
    runAt: Long,
    policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
) {
    val request = OneTimeWorkRequestBuilder<HarnessAutomationWorker>()
        .setInitialDelay((runAt - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        .setInputData(
            Data.Builder()
                .putString(HarnessAutomationScheduler.KEY_TASK_ID, id)
                .putLong(HarnessAutomationScheduler.KEY_SCHEDULE_GENERATION, generation)
                .build(),
        )
        .addTag(HarnessAutomationScheduler.WORK_TAG)
        .build()
    workManager.enqueueUniqueWork(automationWorkName(id, generation), policy, request)
}

internal fun enqueueAutomationPeriodic(
    workManager: WorkManager,
    id: String,
    generation: Long,
    intervalMinutes: Long,
    firstRunAt: Long,
) {
    val request = PeriodicWorkRequestBuilder<HarnessAutomationWorker>(
        intervalMinutes,
        TimeUnit.MINUTES,
    )
        .setInitialDelay(
            (firstRunAt - System.currentTimeMillis()).coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )
        .setInputData(
            Data.Builder()
                .putString(HarnessAutomationScheduler.KEY_TASK_ID, id)
                .putLong(HarnessAutomationScheduler.KEY_SCHEDULE_GENERATION, generation)
                .build(),
        )
        .addTag(HarnessAutomationScheduler.WORK_TAG)
        .build()
    workManager.enqueueUniquePeriodicWork(
        automationWorkName(id, generation),
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
    )
}

internal fun reconcileAutomationSchedules(
    store: AutomationStore,
    workManager: WorkManager,
) {
    store.list()
        .filter { it.status == "scheduled" }
        .forEach { task ->
            val runAt = task.nextRunAt.coerceAtLeast(System.currentTimeMillis())
            if (usesChainedChatScheduling(task) || task.recurringMinutes == null) {
                enqueueAutomationOneTime(
                    workManager,
                    task.id,
                    task.scheduleGeneration,
                    runAt,
                    ExistingWorkPolicy.KEEP,
                )
            } else {
                enqueueAutomationPeriodic(
                    workManager,
                    task.id,
                    task.scheduleGeneration,
                    requireNotNull(task.recurringMinutes),
                    runAt,
                )
            }
        }
}
