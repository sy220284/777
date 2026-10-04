package com.labteto.dshmobile.automation

import android.content.Context
import com.labteto.dshmobile.R
import com.labteto.dshmobile.notify.stableNotificationId
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.notify.DshNotifications
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.labteto.dshmobile.harness.capability.HarnessScheduler
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.local.LocalAutomationWorkException
import com.labteto.dshmobile.local.LocalHarnessBlockedException
import com.labteto.dshmobile.local.automation.LocalAutomationRuntime
import com.labteto.dshmobile.local.truncateWithoutSplittingSurrogatePair
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.charset.StandardCharsets
import java.io.FileOutputStream
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun shouldAutoPauseChatAutomation(
    task: AutomationTask,
    nextFailureStreak: Int,
): Boolean =
    task.mode == AutomationMode.CHAT &&
        task.recurringMinutes != null &&
        nextFailureStreak >= 3

class HarnessAutomationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun localAutomationRuntime(): LocalAutomationRuntime
        fun automationStore(): AutomationStore
        fun automationScheduler(): HarnessAutomationScheduler
        fun notifications(): DshNotifications
        fun hostsStore(): HostsStore
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(HarnessAutomationScheduler.KEY_TASK_ID)
            ?: return Result.failure()
        val manualRun = inputData.getBoolean(HarnessAutomationScheduler.KEY_MANUAL_RUN, false)
        val lease = AutomationExecutionRegistry.tryAcquire(id)
            ?: return if (manualRun) Result.success() else Result.retry()
        return try {
            doOwnedWork(id, manualRun)
        } finally {
            lease.close()
        }
    }

    private suspend fun doOwnedWork(id: String, manualRun: Boolean): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            WorkerEntryPoint::class.java,
        )
        val store = entry.automationStore()
        val scheduler = entry.automationScheduler()
        var task = store.get(id) ?: return Result.success()
        val requestedGeneration = inputData.getLong(
            HarnessAutomationScheduler.KEY_SCHEDULE_GENERATION,
            task.scheduleGeneration,
        )
        if (
            task.scheduleGeneration != requestedGeneration ||
            (task.status in setOf("paused", "waiting_user") && !manualRun)
        ) return Result.success()

        val recovering = !manualRun && task.status == "running" && task.lastRunAt != null
        val started = if (recovering) task.lastRunAt!! else System.currentTimeMillis()
        if (!manualRun) {
            task = store.updateIf(
                id,
                predicate = {
                    it.scheduleGeneration == requestedGeneration &&
                        it.status !in setOf("paused", "waiting_user")
                },
            ) {
                it.copy(
                    status = "running",
                    lastRunAt = started,
                    lastError = null,
                )
            } ?: return Result.success()
        }

        return try {
            val runtime = entry.localAutomationRuntime()
            val workSessionId = if (task.mode == AutomationMode.WORK) {
                runtime.prepareWorkSession(
                    text = task.prompt,
                    preferredSessionId = task.workSessionId,
                ).also { sessionId ->
                    task = store.updateIf(
                        id,
                        predicate = { it.scheduleGeneration == requestedGeneration },
                    ) { current ->
                        current.copy(workSessionId = sessionId)
                    } ?: return Result.success()
                }
            } else {
                task.workSessionId
            }
            val run = when (task.mode) {
                AutomationMode.WORK -> runtime.runWork(
                    text = task.prompt,
                    preferredSessionId = workSessionId,
                    recoverInterrupted = recovering,
                )
                AutomationMode.CHAT -> runtime.runChat(
                    instruction = task.prompt,
                    targetSessionId = requireNotNull(task.targetSessionId) {
                        "角色定时互动缺少目标会话"
                    },
                    recoverInterrupted = recovering,
                    recoveryStartedAt = started,
                    quietHoursEnabled = task.quietHoursEnabled && !manualRun,
                    quietStartHour = task.quietStartHour,
                    quietStartMinute = task.quietStartMinute,
                    quietEndHour = task.quietEndHour,
                    quietEndMinute = task.quietEndMinute,
                    proactiveMinGapMinutes = task.proactiveMinGapMinutes,
                    proactiveMaxUnanswered = task.proactiveMaxUnanswered,
                    minimumSilenceMinutes = if (
                        !manualRun &&
                        task.scheduleType == AutomationScheduleType.SILENCE
                    ) {
                        task.silenceMinutes
                    } else {
                        null
                    },
                    silenceReferenceAt = task.createdAt,
                    bypassProactivePolicy = manualRun,
                )
            }
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
                    entry = entry,
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
                WorkManager.getInstance(applicationContext)
                    .cancelUniqueWork(
                        HarnessAutomationScheduler.workName(id, requestedGeneration),
                    )
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (blocked: LocalHarnessBlockedException) {
            val sessionId = blocked.sessionId ?: task.workSessionId
            val finished = System.currentTimeMillis()
            val detail = blocked.message ?: "需要人工处理"
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
                    entry = entry,
                    task = updated,
                    titleRes = R.string.tasks_notification_blocked,
                    sessionId = sessionId,
                )
            }
            Result.success()
        } catch (error: LocalAutomationWorkException) {
            val finished = System.currentTimeMillis()
            val detail = error.message ?: "后台任务失败"
            if (manualRun) {
                val updated = store.updateIf(
                    id,
                    predicate = { it.scheduleGeneration == requestedGeneration },
                ) { current ->
                    current.copy(
                        workSessionId = error.sessionId,
                        lastError = truncateWithoutSplittingSurrogatePair(detail, 4_000),
                        runReceipts = appendAutomationReceipt(
                            current.runReceipts,
                            AutomationRunReceipt(
                                startedAt = started,
                                finishedAt = finished,
                                status = "failed",
                                sessionId = error.sessionId,
                                errorPreview = truncateWithoutSplittingSurrogatePair(detail, 320),
                            ),
                        ),
                    )
                }
                if (updated != null) {
                    maybeNotify(
                        entry = entry,
                        task = updated,
                        titleRes = R.string.tasks_notification_failed,
                        sessionId = error.sessionId,
                    )
                }
                return Result.success()
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
                    workSessionId = error.sessionId,
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
                            sessionId = error.sessionId,
                            errorPreview = truncateWithoutSplittingSurrogatePair(detail, 320),
                        ),
                    ),
                )
            }
            if (updated != null) {
                maybeNotify(
                    entry = entry,
                    task = updated,
                    titleRes = R.string.tasks_notification_failed,
                    sessionId = error.sessionId,
                )
                when {
                    autoPaused -> {
                        WorkManager.getInstance(applicationContext)
                            .cancelUniqueWork(
                                HarnessAutomationScheduler.workName(id, requestedGeneration),
                            )
                        WorkManager.getInstance(applicationContext)
                            .cancelUniqueWork(HarnessAutomationScheduler.manualWorkName(id))
                    }
                    usesChainedChatScheduling(updated) &&
                        committedNext != null &&
                        updated.status == "scheduled" ->
                        scheduler.enqueueNextChained(id, requireNotNull(committedNext))
                }
            }
            Result.success()
        } catch (error: Throwable) {
            val finished = System.currentTimeMillis()
            val detail = error.message ?: error::class.java.simpleName
            val failureSessionId = task.workSessionId ?: task.targetSessionId
            if (manualRun) {
                val updated = store.updateIf(
                    id,
                    predicate = { it.scheduleGeneration == requestedGeneration },
                ) { current ->
                    current.copy(
                        lastError = truncateWithoutSplittingSurrogatePair(detail, 4_000),
                        runReceipts = appendAutomationReceipt(
                            current.runReceipts,
                            AutomationRunReceipt(
                                startedAt = started,
                                finishedAt = finished,
                                status = "failed",
                                sessionId = failureSessionId,
                                errorPreview = truncateWithoutSplittingSurrogatePair(detail, 320),
                            ),
                        ),
                    )
                }
                if (updated != null) {
                    maybeNotify(
                        entry = entry,
                        task = updated,
                        titleRes = R.string.tasks_notification_failed,
                        sessionId = failureSessionId,
                    )
                }
                return Result.success()
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
                            sessionId = failureSessionId,
                            errorPreview = truncateWithoutSplittingSurrogatePair(detail, 320),
                        ),
                    ),
                )
            }
            if (updated != null) {
                maybeNotify(
                    entry = entry,
                    task = updated,
                    titleRes = R.string.tasks_notification_failed,
                    sessionId = failureSessionId,
                )
                when {
                    autoPaused -> {
                        WorkManager.getInstance(applicationContext)
                            .cancelUniqueWork(
                                HarnessAutomationScheduler.workName(id, requestedGeneration),
                            )
                        WorkManager.getInstance(applicationContext)
                            .cancelUniqueWork(HarnessAutomationScheduler.manualWorkName(id))
                    }
                    usesChainedChatScheduling(updated) &&
                        committedNext != null &&
                        updated.status == "scheduled" ->
                        scheduler.enqueueNextChained(id, requireNotNull(committedNext))
                }
            }
            Result.success()
        }
    }

    private suspend fun maybeNotify(
        entry: WorkerEntryPoint,
        task: AutomationTask,
        titleRes: Int,
        sessionId: String?,
        resultText: String? = null,
    ) {
        if (!task.notify) return
        val enabled = runCatching { entry.hostsStore().settingsOnce().notifyLocalJobs }
            .getOrDefault(true)
        if (!enabled) return
        val chatSuccess = task.mode == AutomationMode.CHAT &&
            titleRes == R.string.tasks_notification_complete
        val title = if (chatSuccess) {
            applicationContext.getString(
                R.string.tasks_chat_notification_title,
                task.actorName?.takeIf(String::isNotBlank)
                    ?: applicationContext.getString(R.string.tasks_chat_character_fallback),
            )
        } else {
            applicationContext.getString(titleRes)
        }
        val text = if (chatSuccess) {
            resultText?.replace("\n", " ")?.trim()?.take(180)
                ?.takeIf(String::isNotBlank)
                ?: applicationContext.getString(R.string.tasks_notification_open_result)
        } else {
            applicationContext.getString(R.string.tasks_notification_open_result)
        }
        entry.notifications().postLocalSession(
            id = stableNotificationId("automation", task.id),
            title = title,
            text = text,
            sessionId = sessionId,
            notificationKey = "automation:${task.id}",
        )
    }

}

