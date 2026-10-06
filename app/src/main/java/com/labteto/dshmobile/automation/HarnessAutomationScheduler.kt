package com.labteto.dshmobile.automation

import com.labteto.dshmobile.local.chat.LocalChatAutomationPolicy
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.labteto.dshmobile.R
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
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.local.runtime.LocalAutomationWorkException
import com.labteto.dshmobile.local.runtime.LocalHarnessBlockedException
import com.labteto.dshmobile.notify.DshNotifications
import com.labteto.dshmobile.notify.stableNotificationId
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

@Singleton
class HarnessAutomationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: AutomationStore,
) : HarnessScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    init {
        reconcileAutomationSchedules(store, workManager)
    }

    override suspend fun schedule(id: String, triggerAtMillis: Long, payload: String) {
        scheduleOnce(id, payload, triggerAtMillis, notify = true)
    }

    override suspend fun cancel(id: String) {
        val task = store.get(id)
        store.remove(id)
        workManager.cancelUniqueWork(workName(id, task?.scheduleGeneration ?: 0L))
        workManager.cancelUniqueWork(manualWorkName(id))
    }

    fun scheduleOnce(
        id: String,
        prompt: String,
        triggerAtMillis: Long,
        notify: Boolean,
        mode: AutomationMode = AutomationMode.WORK,
        targetSessionId: String? = null,
        actorName: String? = null,
        quietHoursEnabled: Boolean = false,
        quietStartHour: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_START_HOUR,
        quietStartMinute: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_START_MINUTE,
        quietEndHour: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_END_HOUR,
        quietEndMinute: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_END_MINUTE,
        proactiveMinGapMinutes: Long = LocalChatAutomationPolicy.DEFAULT_PROACTIVE_MIN_GAP_MINUTES,
        proactiveMaxUnanswered: Int = LocalChatAutomationPolicy.DEFAULT_PROACTIVE_MAX_UNANSWERED,
    ) {
        validateId(id)
        require(prompt.isNotBlank()) { "任务提示词不能为空" }
        validateChatPolicy(
            mode = mode,
            quietStartHour = quietStartHour,
            quietStartMinute = quietStartMinute,
            quietEndHour = quietEndHour,
            quietEndMinute = quietEndMinute,
            proactiveMinGapMinutes = proactiveMinGapMinutes,
            proactiveMaxUnanswered = proactiveMaxUnanswered,
        )
        val now = System.currentTimeMillis()
        val runAt = triggerAtMillis.coerceAtLeast(now)
        store.upsert(
            AutomationTask(
                id = id,
                prompt = prompt,
                createdAt = now,
                nextRunAt = runAt,
                scheduleType = AutomationScheduleType.ONCE,
                scheduleAnchorAt = runAt,
                notify = notify,
                mode = mode,
                targetSessionId = targetSessionId,
                actorName = actorName,
                quietHoursEnabled = quietHoursEnabled,
                quietStartHour = quietStartHour,
                quietStartMinute = quietStartMinute,
                quietEndHour = quietEndHour,
                quietEndMinute = quietEndMinute,
                proactiveMinGapMinutes = proactiveMinGapMinutes,
                proactiveMaxUnanswered = proactiveMaxUnanswered,
            ),
        )
        enqueueOneTime(id, runAt)
    }

    fun schedulePeriodic(
        id: String,
        prompt: String,
        intervalMinutes: Long,
        firstRunAtMillis: Long = System.currentTimeMillis(),
        notify: Boolean,
        mode: AutomationMode = AutomationMode.WORK,
        targetSessionId: String? = null,
        actorName: String? = null,
        quietHoursEnabled: Boolean = false,
        quietStartHour: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_START_HOUR,
        quietStartMinute: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_START_MINUTE,
        quietEndHour: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_END_HOUR,
        quietEndMinute: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_END_MINUTE,
        proactiveMinGapMinutes: Long = LocalChatAutomationPolicy.DEFAULT_PROACTIVE_MIN_GAP_MINUTES,
        proactiveMaxUnanswered: Int = LocalChatAutomationPolicy.DEFAULT_PROACTIVE_MAX_UNANSWERED,
        scheduleType: AutomationScheduleType = AutomationScheduleType.LEGACY,
    ) {
        validateId(id)
        require(prompt.isNotBlank()) { "任务提示词不能为空" }
        require(intervalMinutes >= 15L) { "Android 后台周期任务最短间隔为 15 分钟" }
        checkedAutomationMinutesToMillis(intervalMinutes, "任务周期", allowZero = false)
        require(scheduleType != AutomationScheduleType.SILENCE) {
            "沉默触发请使用 scheduleSilence"
        }
        validateChatPolicy(
            mode = mode,
            quietStartHour = quietStartHour,
            quietStartMinute = quietStartMinute,
            quietEndHour = quietEndHour,
            quietEndMinute = quietEndMinute,
            proactiveMinGapMinutes = proactiveMinGapMinutes,
            proactiveMaxUnanswered = proactiveMaxUnanswered,
        )
        val now = System.currentTimeMillis()
        val firstRun = firstRunAtMillis.coerceAtLeast(now)
        val task = AutomationTask(
            id = id,
            prompt = prompt,
            createdAt = now,
            nextRunAt = firstRun,
            recurringMinutes = intervalMinutes,
            scheduleType = scheduleType,
            scheduleAnchorAt = firstRun,
            notify = notify,
            mode = mode,
            targetSessionId = targetSessionId,
            actorName = actorName,
            quietHoursEnabled = quietHoursEnabled,
            quietStartHour = quietStartHour,
            quietStartMinute = quietStartMinute,
            quietEndHour = quietEndHour,
            quietEndMinute = quietEndMinute,
            proactiveMinGapMinutes = proactiveMinGapMinutes,
            proactiveMaxUnanswered = proactiveMaxUnanswered,
        )
        store.upsert(task)
        if (usesChainedChatScheduling(task)) {
            enqueueOneTime(id, firstRun)
        } else {
            enqueuePeriodic(id, intervalMinutes, firstRun)
        }
    }

    fun scheduleWindow(
        id: String,
        prompt: String,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        notify: Boolean,
        targetSessionId: String,
        actorName: String? = null,
        quietHoursEnabled: Boolean = true,
        quietStartHour: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_START_HOUR,
        quietStartMinute: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_START_MINUTE,
        quietEndHour: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_END_HOUR,
        quietEndMinute: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_END_MINUTE,
        proactiveMinGapMinutes: Long = LocalChatAutomationPolicy.DEFAULT_PROACTIVE_MIN_GAP_MINUTES,
        proactiveMaxUnanswered: Int = LocalChatAutomationPolicy.DEFAULT_PROACTIVE_MAX_UNANSWERED,
    ) {
        validateId(id)
        require(prompt.isNotBlank()) { "任务提示词不能为空" }
        validateWindowMinutes(startMinuteOfDay, endMinuteOfDay)
        validateChatPolicy(
            mode = AutomationMode.CHAT,
            quietStartHour = quietStartHour,
            quietStartMinute = quietStartMinute,
            quietEndHour = quietEndHour,
            quietEndMinute = quietEndMinute,
            proactiveMinGapMinutes = proactiveMinGapMinutes,
            proactiveMaxUnanswered = proactiveMaxUnanswered,
        )
        val now = System.currentTimeMillis()
        val firstRun = firstDailyWindowRun(
            afterMillis = now,
            startMinuteOfDay = startMinuteOfDay,
            endMinuteOfDay = endMinuteOfDay,
        )
        val task = AutomationTask(
            id = id,
            prompt = prompt,
            createdAt = now,
            nextRunAt = firstRun,
            recurringMinutes = 24L * 60L,
            scheduleType = AutomationScheduleType.WINDOW,
            scheduleAnchorAt = firstRun,
            windowStartMinuteOfDay = startMinuteOfDay,
            windowEndMinuteOfDay = endMinuteOfDay,
            notify = notify,
            mode = AutomationMode.CHAT,
            targetSessionId = targetSessionId,
            actorName = actorName,
            quietHoursEnabled = quietHoursEnabled,
            quietStartHour = quietStartHour,
            quietStartMinute = quietStartMinute,
            quietEndHour = quietEndHour,
            quietEndMinute = quietEndMinute,
            proactiveMinGapMinutes = proactiveMinGapMinutes,
            proactiveMaxUnanswered = proactiveMaxUnanswered,
        )
        store.upsert(task)
        enqueueOneTime(id, firstRun)
    }

    fun scheduleSilence(
        id: String,
        prompt: String,
        silenceMinutes: Long,
        notify: Boolean,
        targetSessionId: String,
        actorName: String? = null,
        quietHoursEnabled: Boolean = true,
        quietStartHour: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_START_HOUR,
        quietStartMinute: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_START_MINUTE,
        quietEndHour: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_END_HOUR,
        quietEndMinute: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_END_MINUTE,
        proactiveMinGapMinutes: Long = LocalChatAutomationPolicy.DEFAULT_PROACTIVE_MIN_GAP_MINUTES,
        proactiveMaxUnanswered: Int = LocalChatAutomationPolicy.DEFAULT_PROACTIVE_MAX_UNANSWERED,
    ) {
        validateId(id)
        require(prompt.isNotBlank()) { "任务提示词不能为空" }
        require(silenceMinutes >= LocalChatAutomationPolicy.MIN_SILENCE_MINUTES) { "聊天沉默触发最短为 1 小时" }
        checkedAutomationMinutesToMillis(silenceMinutes, "沉默触发间隔", allowZero = false)
        validateChatPolicy(
            mode = AutomationMode.CHAT,
            quietStartHour = quietStartHour,
            quietStartMinute = quietStartMinute,
            quietEndHour = quietEndHour,
            quietEndMinute = quietEndMinute,
            proactiveMinGapMinutes = proactiveMinGapMinutes,
            proactiveMaxUnanswered = proactiveMaxUnanswered,
        )
        val now = System.currentTimeMillis()
        // Run one lightweight transcript check immediately so an already-silent conversation
        // does not restart its silence clock from task creation.
        val firstRun = now
        store.upsert(
            AutomationTask(
                id = id,
                prompt = prompt,
                createdAt = now,
                nextRunAt = firstRun,
                recurringMinutes = silenceMinutes,
                scheduleType = AutomationScheduleType.SILENCE,
                scheduleAnchorAt = now,
                silenceMinutes = silenceMinutes,
                notify = notify,
                mode = AutomationMode.CHAT,
                targetSessionId = targetSessionId,
                actorName = actorName,
                quietHoursEnabled = quietHoursEnabled,
                quietStartHour = quietStartHour,
                quietStartMinute = quietStartMinute,
                quietEndHour = quietEndHour,
                quietEndMinute = quietEndMinute,
                proactiveMinGapMinutes = proactiveMinGapMinutes,
                proactiveMaxUnanswered = proactiveMaxUnanswered,
            ),
        )
        enqueueOneTime(id, firstRun)
    }

    val tasks: kotlinx.coroutines.flow.StateFlow<List<AutomationTask>> get() = store.tasks

    fun list(): List<AutomationTask> = store.list()

    fun pauseTask(id: String): Boolean {
        val task = store.get(id) ?: return false
        if (task.status == AutomationStatus.PAUSED) return true
        require(task.scheduleGeneration < Long.MAX_VALUE) { "自动任务排程 generation 已耗尽" }
        val paused = store.updateIf(
            id,
            predicate = { it.scheduleGeneration == task.scheduleGeneration },
        ) {
            it.copy(
                status = AutomationStatus.PAUSED,
                scheduleGeneration = it.scheduleGeneration + 1L,
            )
        } ?: return false
        workManager.cancelUniqueWork(workName(id, task.scheduleGeneration))
        workManager.cancelUniqueWork(manualWorkName(id))
        return paused.status == AutomationStatus.PAUSED
    }

    fun resumeTask(id: String): Boolean {
        val task = store.get(id) ?: return false
        if (task.status != AutomationStatus.PAUSED) return false

        val now = System.currentTimeMillis()
        val runAt = when {
            task.scheduleType == AutomationScheduleType.SILENCE -> now
            usesChainedChatScheduling(task) ->
                nextAnchoredAutomationRun(task, now) ?: now
            else -> task.nextRunAt.coerceAtLeast(now)
        }
        val resumed = store.update(id) {
            it.copy(
                status = AutomationStatus.SCHEDULED,
                nextRunAt = runAt,
                lastError = null,
                failureStreak = 0,
            )
        } ?: return false

        if (usesChainedChatScheduling(resumed) || resumed.recurringMinutes == null) {
            enqueueOneTime(id, runAt)
        } else {
            enqueuePeriodic(id, requireNotNull(resumed.recurringMinutes), runAt)
        }
        return true
    }

    fun runTaskNow(id: String): Boolean {
        val task = store.get(id) ?: return false
        val request = OneTimeWorkRequestBuilder<HarnessAutomationWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_TASK_ID, id)
                    .putBoolean(KEY_MANUAL_RUN, true)
                    .putLong(KEY_SCHEDULE_GENERATION, task.scheduleGeneration)
                    .build(),
            )
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(
            manualWorkName(id),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return true
    }

    fun updateTask(
        id: String,
        prompt: String,
        firstRunAtMillis: Long,
        recurringMinutes: Long?,
        scheduleType: AutomationScheduleType,
        silenceMinutes: Long? = null,
        windowStartMinuteOfDay: Int? = null,
        windowEndMinuteOfDay: Int? = null,
        quietHoursEnabled: Boolean,
        quietStartHour: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_START_HOUR,
        quietStartMinute: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_START_MINUTE,
        quietEndHour: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_END_HOUR,
        quietEndMinute: Int = LocalChatAutomationPolicy.DEFAULT_QUIET_END_MINUTE,
        proactiveMinGapMinutes: Long = LocalChatAutomationPolicy.DEFAULT_PROACTIVE_MIN_GAP_MINUTES,
        proactiveMaxUnanswered: Int = LocalChatAutomationPolicy.DEFAULT_PROACTIVE_MAX_UNANSWERED,
    ): Boolean {
        val current = store.get(id) ?: return false
        require(prompt.isNotBlank()) { "任务提示词不能为空" }
        validateChatPolicy(
            mode = current.mode,
            quietStartHour = quietStartHour,
            quietStartMinute = quietStartMinute,
            quietEndHour = quietEndHour,
            quietEndMinute = quietEndMinute,
            proactiveMinGapMinutes = proactiveMinGapMinutes,
            proactiveMaxUnanswered = proactiveMaxUnanswered,
        )
        val now = System.currentTimeMillis()
        val recurring = when (scheduleType) {
            AutomationScheduleType.SILENCE -> {
                require(current.mode == AutomationMode.CHAT) { "沉默触发仅支持聊天模式" }
                require((silenceMinutes ?: 0L) >= LocalChatAutomationPolicy.MIN_SILENCE_MINUTES) { "聊天沉默触发最短为 1 小时" }
                silenceMinutes
            }
            AutomationScheduleType.WINDOW -> {
                require(current.mode == AutomationMode.CHAT) { "随机时间窗仅支持聊天模式" }
                validateWindowMinutes(
                    requireNotNull(windowStartMinuteOfDay),
                    requireNotNull(windowEndMinuteOfDay),
                )
                24L * 60L
            }
            AutomationScheduleType.ONCE -> null
            AutomationScheduleType.DAILY -> {
                require(recurringMinutes == 24L * 60L) { "每日任务周期必须为 24 小时" }
                recurringMinutes
            }
            AutomationScheduleType.WEEKLY -> {
                require(recurringMinutes == 7L * 24L * 60L) { "每周任务周期必须为 7 天" }
                recurringMinutes
            }
            AutomationScheduleType.INTERVAL -> {
                require(recurringMinutes != null) { "自定义周期不能为空" }
                recurringMinutes
            }
            AutomationScheduleType.LEGACY -> recurringMinutes
        }
        if (recurring != null) {
            val minimum = if (current.mode == AutomationMode.CHAT) 60L else 15L
            require(recurring >= minimum) { "任务周期过短" }
            checkedAutomationMinutesToMillis(recurring, "任务周期", allowZero = false)
        }
        val nextRun = when (scheduleType) {
            AutomationScheduleType.SILENCE -> now
            AutomationScheduleType.WINDOW -> firstDailyWindowRun(
                afterMillis = now,
                startMinuteOfDay = requireNotNull(windowStartMinuteOfDay),
                endMinuteOfDay = requireNotNull(windowEndMinuteOfDay),
            )
            else -> firstRunAtMillis.coerceAtLeast(now)
        }
        require(current.scheduleGeneration < Long.MAX_VALUE) { "自动任务排程 generation 已耗尽" }
        val updated = store.updateIf(
            id,
            predicate = { it.scheduleGeneration == current.scheduleGeneration },
        ) { latest ->
            latest.copy(
                prompt = prompt.trim(),
                nextRunAt = nextRun,
                recurringMinutes = recurring,
                scheduleType = scheduleType,
                scheduleAnchorAt = if (scheduleType == AutomationScheduleType.SILENCE) now else nextRun,
                scheduleGeneration = latest.scheduleGeneration + 1L,
                silenceMinutes = if (scheduleType == AutomationScheduleType.SILENCE) silenceMinutes else null,
                windowStartMinuteOfDay = if (scheduleType == AutomationScheduleType.WINDOW) {
                    windowStartMinuteOfDay
                } else null,
                windowEndMinuteOfDay = if (scheduleType == AutomationScheduleType.WINDOW) {
                    windowEndMinuteOfDay
                } else null,
                quietHoursEnabled = quietHoursEnabled,
                quietStartHour = quietStartHour,
                quietStartMinute = quietStartMinute,
                quietEndHour = quietEndHour,
                quietEndMinute = quietEndMinute,
                proactiveMinGapMinutes = proactiveMinGapMinutes,
                proactiveMaxUnanswered = proactiveMaxUnanswered,
                status = if (latest.status == AutomationStatus.PAUSED) AutomationStatus.PAUSED else AutomationStatus.SCHEDULED,
                lastError = null,
                failureStreak = 0,
            )
        } ?: return false
        if (updated.status != AutomationStatus.PAUSED) {
            if (usesChainedChatScheduling(updated) || updated.recurringMinutes == null) {
                enqueueOneTime(id, nextRun)
            } else {
                enqueuePeriodic(id, requireNotNull(updated.recurringMinutes), nextRun)
            }
        }
        workManager.cancelUniqueWork(workName(id, current.scheduleGeneration))
        workManager.cancelUniqueWork(manualWorkName(id))
        return true
    }

    fun onChatUserActivity(sessionId: String, userMessageAt: Long) {
        store.list()
            .filter {
                it.mode == AutomationMode.CHAT &&
                    it.targetSessionId == sessionId
            }
            .forEach { task ->
                var resumedFromWaiting = false
                val updated = store.update(task.id) { current ->
                    if (
                        current.mode != AutomationMode.CHAT ||
                        current.targetSessionId != sessionId
                    ) {
                        current
                    } else {
                        val latestActivity = maxOf(current.lastUserActivityAt ?: Long.MIN_VALUE, userMessageAt)
                        resumedFromWaiting = current.status == AutomationStatus.WAITING_USER
                        if (resumedFromWaiting) {
                            current.copy(
                                status = AutomationStatus.SCHEDULED,
                                nextRunAt = nextAutomationRunAfterUserActivity(current, latestActivity),
                                lastUserActivityAt = latestActivity,
                                lastError = null,
                            )
                        } else {
                            current.copy(lastUserActivityAt = latestActivity)
                        }
                    }
                } ?: return@forEach
                if (updated.status != AutomationStatus.SCHEDULED || !resumedFromWaiting) return@forEach
                if (usesChainedChatScheduling(updated) || updated.recurringMinutes == null) {
                    enqueueOneTime(updated.id, updated.nextRunAt)
                } else {
                    enqueuePeriodic(
                        updated.id,
                        requireNotNull(updated.recurringMinutes),
                        updated.nextRunAt,
                    )
                }
            }
    }

    fun cancelTask(id: String): Boolean {
        val task = store.get(id) ?: return false
        if (!store.remove(id)) return false
        workManager.cancelUniqueWork(workName(id, task.scheduleGeneration))
        workManager.cancelUniqueWork(manualWorkName(id))
        return true
    }

    internal fun enqueueNextChained(id: String, runAt: Long, generation: Long) {
        store.withCurrentGeneration(id, generation) { current ->
            if (current.status == AutomationStatus.SCHEDULED && current.nextRunAt == runAt) {
                enqueueAutomationOneTime(workManager, id, generation, runAt, ExistingWorkPolicy.REPLACE)
            }
        }
    }

    private fun enqueueOneTime(
        id: String,
        runAt: Long,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val generation = store.get(id)?.scheduleGeneration ?: return
        enqueueAutomationOneTime(workManager, id, generation, runAt, policy)
    }

    private fun enqueuePeriodic(id: String, intervalMinutes: Long, firstRunAt: Long) {
        val generation = store.get(id)?.scheduleGeneration ?: return
        enqueueAutomationPeriodic(workManager, id, generation, intervalMinutes, firstRunAt)
    }

    private fun validateChatPolicy(
        mode: AutomationMode,
        quietStartHour: Int,
        quietStartMinute: Int,
        quietEndHour: Int,
        quietEndMinute: Int,
        proactiveMinGapMinutes: Long,
        proactiveMaxUnanswered: Int,
    ) {
        if (mode != AutomationMode.CHAT) return
        LocalChatAutomationPolicy(
            quietStartHour = quietStartHour,
            quietStartMinute = quietStartMinute,
            quietEndHour = quietEndHour,
            quietEndMinute = quietEndMinute,
            proactiveMinGapMinutes = proactiveMinGapMinutes,
            proactiveMaxUnanswered = proactiveMaxUnanswered,
        ).requireValid()
    }

    private fun validateId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "任务编号仅允许字母、数字、点、下划线和横线" }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_MANUAL_RUN = "manual_run"
        const val KEY_SCHEDULE_GENERATION = "schedule_generation"
        const val WORK_TAG = "harness-automation"
        fun workName(id: String, generation: Long = 0L) = automationWorkName(id, generation)
        fun manualWorkName(id: String) = "harness-automation-manual-$id"
    }
}

