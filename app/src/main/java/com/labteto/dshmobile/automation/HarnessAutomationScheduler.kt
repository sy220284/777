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

@Singleton
class HarnessAutomationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: AutomationStore,
) : HarnessScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    init {
        reconcileChainedTasks()
    }

    override suspend fun schedule(id: String, triggerAtMillis: Long, payload: String) {
        scheduleOnce(id, payload, triggerAtMillis, notify = true)
    }

    override suspend fun cancel(id: String) {
        workManager.cancelUniqueWork(workName(id))
        store.remove(id)
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
        quietStartHour: Int = 23,
        quietStartMinute: Int = 0,
        quietEndHour: Int = 7,
        quietEndMinute: Int = 0,
        proactiveMinGapMinutes: Long = 6L * 60L,
        proactiveMaxUnanswered: Int = 2,
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
        quietStartHour: Int = 23,
        quietStartMinute: Int = 0,
        quietEndHour: Int = 7,
        quietEndMinute: Int = 0,
        proactiveMinGapMinutes: Long = 6L * 60L,
        proactiveMaxUnanswered: Int = 2,
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
        quietStartHour: Int = 23,
        quietStartMinute: Int = 0,
        quietEndHour: Int = 7,
        quietEndMinute: Int = 0,
        proactiveMinGapMinutes: Long = 6L * 60L,
        proactiveMaxUnanswered: Int = 2,
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
        quietStartHour: Int = 23,
        quietStartMinute: Int = 0,
        quietEndHour: Int = 7,
        quietEndMinute: Int = 0,
        proactiveMinGapMinutes: Long = 6L * 60L,
        proactiveMaxUnanswered: Int = 2,
    ) {
        validateId(id)
        require(prompt.isNotBlank()) { "任务提示词不能为空" }
        require(silenceMinutes >= 60L) { "聊天沉默触发最短为 1 小时" }
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

    fun list(): List<AutomationTask> = store.list()

    fun pauseTask(id: String): Boolean {
        val task = store.get(id) ?: return false
        if (task.status == "paused") return true
        workManager.cancelUniqueWork(workName(id))
        store.update(id) { it.copy(status = "paused") }
        return true
    }

    fun resumeTask(id: String): Boolean {
        val task = store.get(id) ?: return false
        if (task.status != "paused") return false

        val now = System.currentTimeMillis()
        val runAt = when {
            task.scheduleType == AutomationScheduleType.SILENCE -> now
            usesChainedChatScheduling(task) ->
                nextAnchoredAutomationRun(task, now) ?: now
            else -> task.nextRunAt.coerceAtLeast(now)
        }
        val resumed = store.update(id) {
            it.copy(
                status = "scheduled",
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
        quietStartHour: Int = 23,
        quietStartMinute: Int = 0,
        quietEndHour: Int = 7,
        quietEndMinute: Int = 0,
        proactiveMinGapMinutes: Long = 6L * 60L,
        proactiveMaxUnanswered: Int = 2,
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
                require((silenceMinutes ?: 0L) >= 60L) { "聊天沉默触发最短为 1 小时" }
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
        val wasPaused = current.status == "paused"
        val updated = current.copy(
            prompt = prompt.trim(),
            nextRunAt = nextRun,
            recurringMinutes = recurring,
            scheduleType = scheduleType,
            scheduleAnchorAt = if (scheduleType == AutomationScheduleType.SILENCE) now else nextRun,
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
            status = if (wasPaused) "paused" else "scheduled",
            lastError = null,
            failureStreak = 0,
        )
        store.upsert(updated)
        workManager.cancelUniqueWork(workName(id))
        if (!wasPaused) {
            if (usesChainedChatScheduling(updated) || updated.recurringMinutes == null) {
                enqueueOneTime(id, nextRun)
            } else {
                enqueuePeriodic(id, requireNotNull(updated.recurringMinutes), nextRun)
            }
        }
        return true
    }

    fun onChatUserActivity(sessionId: String, userMessageAt: Long) {
        store.list()
            .filter {
                it.mode == AutomationMode.CHAT &&
                    it.targetSessionId == sessionId &&
                    it.status == "waiting_user"
            }
            .forEach { task ->
                val runAt = when {
                    task.scheduleType == AutomationScheduleType.SILENCE ->
                        checkedAutomationFutureMillis(
                            userMessageAt,
                            requireNotNull(task.silenceMinutes),
                            "沉默触发间隔",
                        )
                    usesChainedChatScheduling(task) ->
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
                val resumed = store.update(task.id) {
                    it.copy(
                        status = "scheduled",
                        nextRunAt = runAt,
                        lastError = null,
                    )
                } ?: return@forEach
                if (usesChainedChatScheduling(resumed) || resumed.recurringMinutes == null) {
                    enqueueOneTime(resumed.id, runAt)
                } else {
                    enqueuePeriodic(
                        resumed.id,
                        requireNotNull(resumed.recurringMinutes),
                        runAt,
                    )
                }
            }
    }

    fun cancelTask(id: String): Boolean {
        workManager.cancelUniqueWork(workName(id))
        workManager.cancelUniqueWork(manualWorkName(id))
        return store.remove(id)
    }

    internal fun enqueueNextChained(id: String, runAt: Long) {
        enqueueOneTime(id, runAt)
    }

    private fun reconcileChainedTasks() {
        store.list()
            .filter { it.status == "scheduled" && usesChainedChatScheduling(it) }
            .forEach { task ->
                enqueueOneTime(
                    id = task.id,
                    runAt = task.nextRunAt.coerceAtLeast(System.currentTimeMillis()),
                    policy = ExistingWorkPolicy.KEEP,
                )
            }
    }

    private fun enqueueOneTime(
        id: String,
        runAt: Long,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val now = System.currentTimeMillis()
        val request = OneTimeWorkRequestBuilder<HarnessAutomationWorker>()
            .setInitialDelay((runAt - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(KEY_TASK_ID, id).build())
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(workName(id), policy, request)
    }

    private fun enqueuePeriodic(id: String, intervalMinutes: Long, firstRunAt: Long) {
        val now = System.currentTimeMillis()
        val request = PeriodicWorkRequestBuilder<HarnessAutomationWorker>(
            intervalMinutes,
            TimeUnit.MINUTES,
        )
            .setInitialDelay((firstRunAt - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(KEY_TASK_ID, id).build())
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            workName(id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
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
        require(quietStartHour in 0..23 && quietEndHour in 0..23) { "免打扰小时无效" }
        require(quietStartMinute in 0..59 && quietEndMinute in 0..59) { "免打扰分钟无效" }
        require(proactiveMinGapMinutes >= 60L) { "主动互动最低间隔至少 1 小时" }
        require(proactiveMaxUnanswered in 1..5) { "连续未回复上限必须为 1 到 5 次" }
    }

    private fun validateId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "任务编号仅允许字母、数字、点、下划线和横线" }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_MANUAL_RUN = "manual_run"
        const val WORK_TAG = "harness-automation"
        fun workName(id: String) = "harness-automation-$id"
        fun manualWorkName(id: String) = "harness-automation-manual-$id"
    }
}

