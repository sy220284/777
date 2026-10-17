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
 * Manual runs do not advance lastRunAt and retain their generation-only contract.
 */
internal fun ownsAutomationSettlement(
    task: AutomationTask,
    generation: Long,
    startedAt: Long,
    manualRun: Boolean,
): Boolean = task.scheduleGeneration == generation &&
    (manualRun || (task.status == AutomationStatus.RUNNING && task.lastRunAt == startedAt))
