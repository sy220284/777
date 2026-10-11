package com.labteto.dshmobile.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.harness.capability.HarnessScheduler
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.local.automation.LocalAutomationRuntime
import com.labteto.dshmobile.local.chat.LocalChatAutomationPolicy
import com.labteto.dshmobile.local.automation.LocalAutomationRunStatus
import com.labteto.dshmobile.notify.DshNotifications
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
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
        val preflightTask = store.get(id) ?: return Result.success()
        val requestedGeneration = inputData.getLong(
            HarnessAutomationScheduler.KEY_SCHEDULE_GENERATION,
            preflightTask.scheduleGeneration,
        )
        if (
            preflightTask.scheduleGeneration != requestedGeneration ||
            (preflightTask.status in setOf(AutomationStatus.PAUSED, AutomationStatus.WAITING_USER) && !manualRun)
        ) return Result.success()

        // A manual request is a real user action, not a disposable probe. If another trigger owns
        // the task, keep this WorkManager request pending instead of reporting a false success.
        val lease = AutomationExecutionRegistry.tryAcquire(id) ?: return Result.retry()
        return try {
            doOwnedWork(id, manualRun, entry, requestedGeneration)
        } finally {
            lease.close()
        }
    }

    private suspend fun doOwnedWork(
        id: String,
        manualRun: Boolean,
        entry: WorkerEntryPoint,
        requestedGeneration: Long,
    ): Result {
        val store = entry.automationStore()
        val scheduler = entry.automationScheduler()
        val settlement = AutomationWorkerSettlementCoordinator(
            context = applicationContext,
            store = store,
            scheduler = scheduler,
            notifications = entry.notifications(),
            hostsStore = entry.hostsStore(),
        )
        // Re-check after acquiring the task lease. Editing/pausing can race the preflight read;
        // an old generation must release ownership before it creates sessions or starts side effects.
        var task = store.get(id) ?: return Result.success()
        if (
            task.scheduleGeneration != requestedGeneration ||
            (task.status in setOf(AutomationStatus.PAUSED, AutomationStatus.WAITING_USER) && !manualRun)
        ) return Result.success()

        val recovering = !manualRun && task.status == AutomationStatus.RUNNING && task.lastRunAt != null
        val started = if (recovering) task.lastRunAt!! else System.currentTimeMillis()
        if (!manualRun) {
            task = store.updateIf(
                id,
                predicate = {
                    it.scheduleGeneration == requestedGeneration &&
                        it.status !in setOf(AutomationStatus.PAUSED, AutomationStatus.WAITING_USER)
                },
            ) {
                it.copy(
                    status = AutomationStatus.RUNNING,
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
                    policy = LocalChatAutomationPolicy(
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
                    ),
                )
            }
            when (run.status) {
                LocalAutomationRunStatus.DELIVERED,
                LocalAutomationRunStatus.SKIPPED -> settlement.settleSuccess(
                    id = id,
                    requestedGeneration = requestedGeneration,
                    task = task,
                    manualRun = manualRun,
                    started = started,
                    run = run,
                )
                LocalAutomationRunStatus.BLOCKED -> settlement.settleBlocked(
                    id = id,
                    requestedGeneration = requestedGeneration,
                    task = task,
                    manualRun = manualRun,
                    started = started,
                    sessionId = run.sessionId,
                    detail = run.detail ?: run.output,
                )
                LocalAutomationRunStatus.CANCELLED -> settlement.settleFailure(
                    id = id,
                    requestedGeneration = requestedGeneration,
                    task = task,
                    manualRun = manualRun,
                    started = started,
                    sessionId = run.sessionId,
                    detail = run.detail ?: run.output,
                    persistWorkSessionId = task.mode == AutomationMode.WORK,
                    receiptStatus = AutomationStatus.CANCELLED,
                )
                LocalAutomationRunStatus.FAILED -> settlement.settleFailure(
                    id = id,
                    requestedGeneration = requestedGeneration,
                    task = task,
                    manualRun = manualRun,
                    started = started,
                    sessionId = run.sessionId,
                    detail = run.detail ?: run.output,
                    persistWorkSessionId = task.mode == AutomationMode.WORK,
                )
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            settlement.settleFailure(
                id = id,
                requestedGeneration = requestedGeneration,
                task = task,
                manualRun = manualRun,
                started = started,
                sessionId = task.workSessionId ?: task.targetSessionId,
                detail = error.message ?: error::class.java.simpleName,
                persistWorkSessionId = false,
            )
            Result.success()
        }
    }

}

