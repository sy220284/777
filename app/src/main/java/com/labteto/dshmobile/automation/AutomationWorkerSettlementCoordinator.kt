package com.labteto.dshmobile.automation

import android.content.Context
import androidx.work.WorkManager
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.local.LocalAutomationRunResult
import com.labteto.dshmobile.local.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.notify.DshNotifications
import com.labteto.dshmobile.notify.stableNotificationId

/**
 * Owns Automation Worker terminal settlement after the runtime execution has finished.
 *
 * Every durable write is conditional on the generation captured when the Worker started. A stale
 * Worker may finish in memory, but it cannot publish state, notifications or future scheduling.
 */
internal class AutomationWorkerSettlementCoordinator(
    private val context: Context,
    private val store: AutomationStore,
    private val scheduler: HarnessAutomationScheduler,
    private val notifications: DshNotifications,
    private val hostsStore: HostsStore,
) {
    suspend fun settleSuccess(
        id: String,
        requestedGeneration: Long,
        task: AutomationTask,
        manualRun: Boolean,
        started: Long,
        run: LocalAutomationRunResult,
    ) {
        val finished = System.currentTimeMillis()
        val chained = !manualRun && usesChainedChatScheduling(task)
        val waitingRequested = !manualRun &&
            run.waitingForUserReply &&
            task.mode == AutomationMode.CHAT &&
            task.recurringMinutes != null
        val deferredOneShot = !manualRun &&
            task.recurringMinutes == null &&
            !run.delivered &&
            run.nextRunAtHint != null
        val receiptStatus = if (run.delivered) "completed" else "skipped"
        val resultText = run.skipReason ?: run.output
        var committedNext: Long? = null
        var committedWaiting = false
        val updated = store.updateIf(
            id,
            predicate = { it.scheduleGeneration == requestedGeneration },
        ) { current ->
            val userActivityDuringRun = current.lastUserActivityAt
                ?.takeIf { it >= started }
            committedWaiting = waitingRequested && userActivityDuringRun == null
            committedNext = when {
                manualRun -> current.nextRunAt
                waitingRequested && userActivityDuringRun != null ->
                    nextAutomationRunAfterUserActivity(current, userActivityDuringRun)
                committedWaiting -> null
                chained -> nextAnchoredAutomationRun(
                    task = current,
                    afterMillis = finished,
                    suggestedRunAt = run.nextRunAtHint,
                )
                deferredOneShot -> run.nextRunAtHint
                current.recurringMinutes != null ->
                    checkedAutomationFutureMillis(finished, current.recurringMinutes, "任务周期")
                else -> current.nextRunAt
            }
            val nextStatus = when {
                manualRun -> current.status
                committedWaiting -> "waiting_user"
                chained || deferredOneShot || current.recurringMinutes != null -> "scheduled"
                else -> "completed"
            }
            current.copy(
                workSessionId = if (current.mode == AutomationMode.WORK) {
                    run.sessionId
                } else {
                    current.workSessionId
                },
                status = nextStatus,
                nextRunAt = if (manualRun) current.nextRunAt else committedNext ?: current.nextRunAt,
                lastResult = truncateWithoutSplittingSurrogatePair(resultText, 20_000),
                lastError = null,
                failureStreak = if (manualRun) current.failureStreak else 0,
                runReceipts = appendAutomationReceipt(
                    current.runReceipts,
                    AutomationRunReceipt(
                        startedAt = started,
                        finishedAt = finished,
                        status = receiptStatus,
                        sessionId = run.sessionId,
                        resultPreview = truncateWithoutSplittingSurrogatePair(
                            resultText.replace("\n", " "),
                            320,
                        ),
                    ),
                ),
            )
        }
        if (updated != null && run.delivered) {
            maybeNotify(
                task = updated,
                titleRes = R.string.tasks_notification_complete,
                sessionId = run.sessionId,
                resultText = run.output,
            )
        }
        if (
            updated?.status == "scheduled" &&
            committedNext != null &&
            (usesChainedChatScheduling(updated) || updated.recurringMinutes == null)
        ) {
            scheduler.enqueueNextChained(id, requireNotNull(committedNext))
        }
        if (updated != null && committedWaiting) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(HarnessAutomationScheduler.workName(id, requestedGeneration))
        }
    }

    suspend fun settleBlocked(
        id: String,
        requestedGeneration: Long,
        task: AutomationTask,
        manualRun: Boolean,
        started: Long,
        sessionId: String?,
        detail: String,
    ) {
        val finished = System.currentTimeMillis()
        val updated = store.updateIf(
            id,
            predicate = { it.scheduleGeneration == requestedGeneration },
        ) { current ->
            current.copy(
                workSessionId = sessionId ?: current.workSessionId,
                status = if (manualRun) current.status else "blocked",
                lastError = truncateWithoutSplittingSurrogatePair(detail, 4_000),
                runReceipts = appendAutomationReceipt(
                    current.runReceipts,
                    AutomationRunReceipt(
                        startedAt = started,
                        finishedAt = finished,
                        status = "blocked",
                        sessionId = sessionId,
                        errorPreview = truncateWithoutSplittingSurrogatePair(detail, 320),
                    ),
                ),
            )
        }
        if (updated != null) {
            maybeNotify(
                task = updated,
                titleRes = R.string.tasks_notification_blocked,
                sessionId = sessionId,
            )
        }
    }

    suspend fun settleFailure(
        id: String,
        requestedGeneration: Long,
        task: AutomationTask,
        manualRun: Boolean,
        started: Long,
        sessionId: String?,
        detail: String,
        persistWorkSessionId: Boolean,
    ) {
        val finished = System.currentTimeMillis()
        if (manualRun) {
            val updated = store.updateIf(
                id,
                predicate = { it.scheduleGeneration == requestedGeneration },
            ) { current ->
                current.copy(
                    workSessionId = if (persistWorkSessionId) sessionId else current.workSessionId,
                    lastError = truncateWithoutSplittingSurrogatePair(detail, 4_000),
                    runReceipts = appendAutomationReceipt(
                        current.runReceipts,
                        AutomationRunReceipt(
                            startedAt = started,
                            finishedAt = finished,
                            status = "failed",
                            sessionId = sessionId,
                            errorPreview = truncateWithoutSplittingSurrogatePair(detail, 320),
                        ),
                    ),
                )
            }
            if (updated != null) {
                maybeNotify(
                    task = updated,
                    titleRes = R.string.tasks_notification_failed,
                    sessionId = sessionId,
                )
            }
            return
        }

        var autoPaused = false
        var committedNext: Long? = null
        val updated = store.updateIf(
            id,
            predicate = { it.scheduleGeneration == requestedGeneration },
        ) { current ->
            val nextFailureStreak = current.failureStreak.saturatingIncrement()
            autoPaused = shouldAutoPauseChatAutomation(current, nextFailureStreak)
            val chained = usesChainedChatScheduling(current)
            committedNext = if (chained) {
                nextAnchoredAutomationRun(current, finished)
            } else {
                current.recurringMinutes?.let {
                    checkedAutomationFutureMillis(finished, it, "任务周期")
                }
            }
            current.copy(
                workSessionId = if (persistWorkSessionId) sessionId else current.workSessionId,
                status = when {
                    autoPaused -> "paused"
                    current.recurringMinutes == null -> "failed"
                    else -> "scheduled"
                },
                nextRunAt = committedNext ?: current.nextRunAt,
                lastError = truncateWithoutSplittingSurrogatePair(detail, 4_000),
                failureStreak = nextFailureStreak,
                runReceipts = appendAutomationReceipt(
                    current.runReceipts,
                    AutomationRunReceipt(
                        startedAt = started,
                        finishedAt = finished,
                        status = "failed",
                        sessionId = sessionId,
                        errorPreview = truncateWithoutSplittingSurrogatePair(detail, 320),
                    ),
                ),
            )
        }
        if (updated == null) return

        maybeNotify(
            task = updated,
            titleRes = R.string.tasks_notification_failed,
            sessionId = sessionId,
        )
        when {
            autoPaused -> {
                WorkManager.getInstance(context)
                    .cancelUniqueWork(HarnessAutomationScheduler.workName(id, requestedGeneration))
                WorkManager.getInstance(context)
                    .cancelUniqueWork(HarnessAutomationScheduler.manualWorkName(id))
            }
            usesChainedChatScheduling(updated) &&
                committedNext != null &&
                updated.status == "scheduled" ->
                scheduler.enqueueNextChained(id, requireNotNull(committedNext))
        }
    }

    private suspend fun maybeNotify(
        task: AutomationTask,
        titleRes: Int,
        sessionId: String?,
        resultText: String? = null,
    ) {
        if (!task.notify) return
        val enabled = runCatching { hostsStore.settingsOnce().notifyLocalJobs }
            .getOrDefault(true)
        if (!enabled) return
        val chatSuccess = task.mode == AutomationMode.CHAT &&
            titleRes == R.string.tasks_notification_complete
        val title = if (chatSuccess) {
            context.getString(
                R.string.tasks_chat_notification_title,
                task.actorName?.takeIf(String::isNotBlank)
                    ?: context.getString(R.string.tasks_chat_character_fallback),
            )
        } else {
            context.getString(titleRes)
        }
        val text = if (chatSuccess) {
            resultText?.replace("\n", " ")?.trim()?.take(180)
                ?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.tasks_notification_open_result)
        } else {
            context.getString(R.string.tasks_notification_open_result)
        }
        notifications.postLocalSession(
            id = stableNotificationId("automation", task.id),
            title = title,
            text = text,
            sessionId = sessionId,
            notificationKey = "automation:${task.id}",
        )
    }
}
