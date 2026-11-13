package com.labteto.dshmobile.automation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext

/** A background caller owns its run even when the scheduler launches it in another scope. */
internal suspend fun <T> owningAutomationRun(job: Job, awaitResult: suspend () -> T): T = try {
    awaitResult()
} catch (cancelled: CancellationException) {
    withContext(NonCancellable) { job.cancelAndJoin() }
    throw cancelled
}

/** A scheduled Worker may claim only a fresh schedule or an interrupted, durable run. */
internal fun mayStartScheduledAutomation(task: AutomationTask, generation: Long): Boolean =
    task.scheduleGeneration == generation &&
        task.status in setOf(AutomationStatus.SCHEDULED, AutomationStatus.RUNNING)

/**
 * Generation fences edits, but not two executions of the SAME schedule generation.
 * Check the original durable startedAt as well, within AutomationStore.updateIf's lock,
 * so an old Worker cannot settle a newer run or settle twice after restart.
 * Manual runs retain the scheduled task's lastRunAt/status, but use their
 * durable WorkManager request UUID as an independent settlement capability.
 * Editing/replacing a manual request revokes the old UUID before enqueuing
 * its replacement. A stale Worker cannot publish even within one generation.
 */
internal fun ownsAutomationSettlement(
    task: AutomationTask,
    generation: Long,
    startedAt: Long,
    manualRun: Boolean,
    manualExecutionId: String? = null,
): Boolean = task.scheduleGeneration == generation &&
    (if (manualRun) {
        !manualExecutionId.isNullOrBlank() && task.activeManualRunId == manualExecutionId &&
            task.activeManualStartedAt == startedAt
    } else {
        task.status == AutomationStatus.RUNNING && task.lastRunAt == startedAt
    })

/** A one-off manual receipt must never replace an already armed scheduled chain. */
internal fun mayEnqueueNextAfterAutomationSettlement(
    manualRun: Boolean,
    updated: AutomationTask?,
    nextRunAt: Long?,
): Boolean = !manualRun && updated?.status == AutomationStatus.SCHEDULED &&
    nextRunAt != null &&
    (usesChainedAutomationScheduling(updated) || updated.recurringMinutes == null)
