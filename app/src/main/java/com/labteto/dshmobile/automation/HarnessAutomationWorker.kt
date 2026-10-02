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
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            WorkerEntryPoint::class.java,
        )
        val store = entry.automationStore()
        val scheduler = entry.automationScheduler()
        var task = store.get(id) ?: return Result.success()
        if ((!manualRun && task.scheduleGeneration != inputData.getLong(HarnessAutomationScheduler.KEY_SCHEDULE_GENERATION, 0L)) ||
            (task.status in setOf("paused", "waiting_user") && !manualRun)
        ) return Result.success()

        val recovering = !manualRun && task.status == "running" && task.lastRunAt != null
        val started = if (recovering) task.lastRunAt!! else System.currentTimeMillis()
        if (!manualRun) {
            store.update(id) {
                it.copy(
                    status = "running",
                    lastRunAt = started,
                    lastError = null,
                )
            }
            task = store.get(id) ?: return Result.success()
        }

        return try {
            val runtime = entry.localAutomationRuntime()
            val workSessionId = if (task.mode == AutomationMode.WORK) {
                runtime.prepareWorkSession(
                    text = task.prompt,
                    preferredSessionId = task.workSessionId,
                ).also { sessionId ->
                    store.update(id) { current ->
                        current.copy(workSessionId = sessionId)
                    }
                    task = store.get(id) ?: task.copy(workSessionId = sessionId)
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
            val waitingForUserReply = !manualRun &&
                run.waitingForUserReply &&
                task.mode == AutomationMode.CHAT &&
                task.recurringMinutes != null
            val deferredOneShot = !manualRun &&
                task.recurringMinutes == null &&
                !run.delivered &&
                run.nextRunAtHint != null
            val next = when {
                waitingForUserReply -> null
                chained -> nextAnchoredAutomationRun(
                    task = task,
                    afterMillis = finished,
                    suggestedRunAt = run.nextRunAtHint,
                )
                deferredOneShot -> run.nextRunAtHint
                !manualRun && task.recurringMinutes != null ->
                    checkedAutomationFutureMillis(finished, task.recurringMinutes, "任务周期")
                else -> task.nextRunAt
            }
            val receiptStatus = if (run.delivered) "completed" else "skipped"
            val resultText = run.skipReason ?: run.output
            val updated = store.update(id) { current ->
                val nextStatus = when {
                    manualRun -> current.status
                    waitingForUserReply -> "waiting_user"
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
                    nextRunAt = if (manualRun) current.nextRunAt else next ?: current.nextRunAt,
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
            if (run.delivered) {
                maybeNotify(
                    entry = entry,
                    task = task,
                    titleRes = R.string.tasks_notification_complete,
                    sessionId = run.sessionId,
                    resultText = run.output,
                )
            }
            if (
                (chained || deferredOneShot) &&
                next != null &&
                updated?.status == "scheduled"
            ) {
                scheduler.enqueueNextChained(id, next)
            }
            if (waitingForUserReply) {
                WorkManager.getInstance(applicationContext)
                    .cancelUniqueWork(HarnessAutomationScheduler.workName(id))
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (blocked: LocalHarnessBlockedException) {
            val sessionId = blocked.sessionId ?: task.workSessionId
            val finished = System.currentTimeMillis()
            val detail = blocked.message ?: "需要人工处理"
            store.update(id) { current ->
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
            maybeNotify(
                entry = entry,
                task = task,
                titleRes = R.string.tasks_notification_blocked,
                sessionId = sessionId,
            )
            Result.success()
        } catch (error: LocalAutomationWorkException) {
            val finished = System.currentTimeMillis()
            val detail = error.message ?: "后台任务失败"
            if (manualRun) {
                store.update(id) { current ->
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
                maybeNotify(
                    entry = entry,
                    task = task,
                    titleRes = R.string.tasks_notification_failed,
                    sessionId = error.sessionId,
                )
                return Result.success()
            }

            var autoPaused = false
            val chained = usesChainedChatScheduling(task)
            val next = if (chained) {
                nextAnchoredAutomationRun(task, finished)
            } else {
                task.recurringMinutes?.let { checkedAutomationFutureMillis(finished, it, "任务周期") }
            }
            val updated = store.update(id) { current ->
                val nextFailureStreak = current.failureStreak.saturatingIncrement()
                autoPaused = shouldAutoPauseChatAutomation(current, nextFailureStreak)
                current.copy(
                    workSessionId = error.sessionId,
                    status = when {
                        autoPaused -> "paused"
                        current.recurringMinutes == null -> "failed"
                        else -> "scheduled"
                    },
                    nextRunAt = next ?: current.nextRunAt,
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
            maybeNotify(
                entry = entry,
                task = task,
                titleRes = R.string.tasks_notification_failed,
                sessionId = error.sessionId,
            )
            when {
                autoPaused ->
                    WorkManager.getInstance(applicationContext)
                        .cancelUniqueWork(HarnessAutomationScheduler.workName(id))
                chained && next != null && updated?.status == "scheduled" ->
                    scheduler.enqueueNextChained(id, next)
            }
            Result.success()
        } catch (error: Throwable) {
            val finished = System.currentTimeMillis()
            val detail = error.message ?: error::class.java.simpleName
            if (manualRun) {
                store.update(id) { current ->
                    current.copy(
                        lastError = truncateWithoutSplittingSurrogatePair(detail, 4_000),
                        runReceipts = appendAutomationReceipt(
                            current.runReceipts,
                            AutomationRunReceipt(
                                startedAt = started,
                                finishedAt = finished,
                                status = "failed",
                                sessionId = task.workSessionId ?: task.targetSessionId,
                                errorPreview = truncateWithoutSplittingSurrogatePair(detail, 320),
                            ),
                        ),
                    )
                }
                maybeNotify(
                    entry = entry,
                    task = task,
                    titleRes = R.string.tasks_notification_failed,
                    sessionId = task.workSessionId ?: task.targetSessionId,
                )
                return Result.success()
            }

            var autoPaused = false
            val chained = usesChainedChatScheduling(task)
            val next = if (chained) {
                nextAnchoredAutomationRun(task, finished)
            } else {
                task.recurringMinutes?.let { checkedAutomationFutureMillis(finished, it, "任务周期") }
            }
            val updated = store.update(id) { current ->
                val nextFailureStreak = current.failureStreak.saturatingIncrement()
                autoPaused = shouldAutoPauseChatAutomation(current, nextFailureStreak)
                current.copy(
                    status = when {
                        autoPaused -> "paused"
                        current.recurringMinutes == null -> "failed"
                        else -> "scheduled"
                    },
                    nextRunAt = next ?: current.nextRunAt,
                    lastError = truncateWithoutSplittingSurrogatePair(detail, 4_000),
                    failureStreak = nextFailureStreak,
                    runReceipts = appendAutomationReceipt(
                        current.runReceipts,
                        AutomationRunReceipt(
                            startedAt = started,
                            finishedAt = finished,
                            status = "failed",
                            sessionId = task.workSessionId ?: task.targetSessionId,
                            errorPreview = truncateWithoutSplittingSurrogatePair(detail, 320),
                        ),
                    ),
                )
            }
            maybeNotify(
                entry = entry,
                task = task,
                titleRes = R.string.tasks_notification_failed,
                sessionId = task.workSessionId ?: task.targetSessionId,
            )
            when {
                autoPaused ->
                    WorkManager.getInstance(applicationContext)
                        .cancelUniqueWork(HarnessAutomationScheduler.workName(id))
                chained && next != null && updated?.status == "scheduled" ->
                    scheduler.enqueueNextChained(id, next)
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

