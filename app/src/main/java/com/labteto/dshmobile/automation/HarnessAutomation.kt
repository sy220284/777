package com.labteto.dshmobile.automation

import android.content.Context
import com.labteto.dshmobile.R
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
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.truncateWithoutSplittingSurrogatePair
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit
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

@Serializable
enum class AutomationMode {
    WORK,
    CHAT,
}

@Serializable
enum class AutomationScheduleType {
    LEGACY,
    ONCE,
    INTERVAL,
    DAILY,
    WEEKLY,
    SILENCE,
}

@Serializable
data class AutomationRunReceipt(
    val startedAt: Long,
    val finishedAt: Long,
    val status: String,
    val sessionId: String? = null,
    val resultPreview: String? = null,
    val errorPreview: String? = null,
)

internal fun appendAutomationReceipt(
    current: List<AutomationRunReceipt>,
    receipt: AutomationRunReceipt,
): List<AutomationRunReceipt> {
    val cutoff = receipt.finishedAt - AUTOMATION_HISTORY_DAYS * 24L * 60L * 60L * 1000L
    return (current + receipt)
        .filter { it.finishedAt >= cutoff }
        .takeLast(AUTOMATION_HISTORY_RECORDS)
}

private const val AUTOMATION_HISTORY_DAYS = 30L
private const val AUTOMATION_HISTORY_RECORDS = 200

internal fun usesChainedChatScheduling(task: AutomationTask): Boolean =
    task.mode == AutomationMode.CHAT &&
        task.scheduleType in setOf(
            AutomationScheduleType.INTERVAL,
            AutomationScheduleType.DAILY,
            AutomationScheduleType.WEEKLY,
            AutomationScheduleType.SILENCE,
        )

internal fun nextAnchoredAutomationRun(
    task: AutomationTask,
    afterMillis: Long,
    suggestedRunAt: Long? = null,
): Long? {
    suggestedRunAt?.takeIf { it > afterMillis }?.let { return it }

    if (task.scheduleType == AutomationScheduleType.SILENCE) {
        return task.silenceMinutes?.let { afterMillis + it * 60_000L }
    }

    val anchor = task.scheduleAnchorAt ?: task.nextRunAt
    return when (task.scheduleType) {
        AutomationScheduleType.DAILY ->
            nextCalendarAnchoredRun(anchor, afterMillis, Calendar.DAY_OF_YEAR, 1)
        AutomationScheduleType.WEEKLY ->
            nextCalendarAnchoredRun(anchor, afterMillis, Calendar.WEEK_OF_YEAR, 1)
        AutomationScheduleType.INTERVAL -> {
            val minutes = task.recurringMinutes ?: return null
            nextIntervalAnchoredRun(anchor, afterMillis, minutes)
        }
        else -> task.recurringMinutes?.let { afterMillis + it * 60_000L }
    }
}

internal fun nextIntervalAnchoredRun(
    anchorMillis: Long,
    afterMillis: Long,
    intervalMinutes: Long,
): Long {
    require(intervalMinutes > 0L) { "intervalMinutes must be positive" }
    val intervalMillis = intervalMinutes * 60_000L
    if (anchorMillis > afterMillis) return anchorMillis
    val elapsed = afterMillis - anchorMillis
    val steps = elapsed / intervalMillis + 1L
    return anchorMillis + steps * intervalMillis
}

private fun nextCalendarAnchoredRun(
    anchorMillis: Long,
    afterMillis: Long,
    field: Int,
    amount: Int,
): Long {
    val calendar = Calendar.getInstance().apply { timeInMillis = anchorMillis }
    while (calendar.timeInMillis <= afterMillis) {
        calendar.add(field, amount)
    }
    return calendar.timeInMillis
}

@Serializable
data class AutomationTask(
    val id: String,
    val prompt: String,
    val createdAt: Long,
    val nextRunAt: Long,
    val recurringMinutes: Long? = null,
    val scheduleType: AutomationScheduleType = AutomationScheduleType.LEGACY,
    val scheduleAnchorAt: Long? = null,
    val silenceMinutes: Long? = null,
    val notify: Boolean = true,
    /** Existing tasks decode as WORK; chat interactions explicitly bind to a durable chat session. */
    val mode: AutomationMode = AutomationMode.WORK,
    val targetSessionId: String? = null,
    val actorName: String? = null,
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 23,
    val quietEndHour: Int = 7,
    val failureStreak: Int = 0,
    /** Dedicated Work-mode session that owns this task's run history and artifacts. */
    val workSessionId: String? = null,
    val status: String = "scheduled",
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
    val lastError: String? = null,
    val runReceipts: List<AutomationRunReceipt> = emptyList(),
)

@Serializable
private data class AutomationDocument(
    val version: Int = 1,
    val tasks: List<AutomationTask> = emptyList(),
)

@Singleton
class AutomationStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val file = File(context.filesDir, "local-harness/automations.json")

    @Synchronized
    fun list(): List<AutomationTask> = read().tasks.sortedBy { it.nextRunAt }

    @Synchronized
    fun get(id: String): AutomationTask? = read().tasks.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(task: AutomationTask) {
        val current = read().tasks.filterNot { it.id == task.id } + task
        write(AutomationDocument(tasks = current))
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val document = read()
        val remaining = document.tasks.filterNot { it.id == id }
        if (remaining.size == document.tasks.size) return false
        write(document.copy(tasks = remaining))
        return true
    }

    @Synchronized
    fun update(id: String, transform: (AutomationTask) -> AutomationTask): AutomationTask? {
        val document = read()
        val task = document.tasks.firstOrNull { it.id == id } ?: return null
        val updated = transform(task)
        write(document.copy(tasks = document.tasks.map { if (it.id == id) updated else it }))
        return updated
    }

    private fun read(): AutomationDocument {
        if (!file.isFile) return AutomationDocument()
        return runCatching {
            json.decodeFromString(AutomationDocument.serializer(), file.readText())
        }.getOrElse {
            val corrupt = File(file.parentFile, "automations.corrupt-${System.currentTimeMillis()}.json")
            runCatching { file.copyTo(corrupt, overwrite = true) }
            AutomationDocument()
        }
    }

    private fun write(document: AutomationDocument) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(AutomationDocument.serializer(), document))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }
}

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
        quietEndHour: Int = 7,
    ) {
        validateId(id)
        require(prompt.isNotBlank()) { "任务提示词不能为空" }
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
                quietEndHour = quietEndHour,
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
        quietEndHour: Int = 7,
        scheduleType: AutomationScheduleType = AutomationScheduleType.LEGACY,
    ) {
        validateId(id)
        require(prompt.isNotBlank()) { "任务提示词不能为空" }
        require(intervalMinutes >= 15L) { "Android 后台周期任务最短间隔为 15 分钟" }
        require(scheduleType != AutomationScheduleType.SILENCE) {
            "沉默触发请使用 scheduleSilence"
        }
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
            quietEndHour = quietEndHour,
        )
        store.upsert(task)
        if (usesChainedChatScheduling(task)) {
            enqueueOneTime(id, firstRun)
        } else {
            enqueuePeriodic(id, intervalMinutes, firstRun)
        }
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
        quietEndHour: Int = 7,
    ) {
        validateId(id)
        require(prompt.isNotBlank()) { "任务提示词不能为空" }
        require(silenceMinutes >= 60L) { "聊天沉默触发最短为 1 小时" }
        val now = System.currentTimeMillis()
        val firstRun = now + silenceMinutes * 60_000L
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
                quietEndHour = quietEndHour,
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
        quietHoursEnabled: Boolean,
        quietStartHour: Int = 23,
        quietEndHour: Int = 7,
    ): Boolean {
        val current = store.get(id) ?: return false
        require(prompt.isNotBlank()) { "任务提示词不能为空" }
        val now = System.currentTimeMillis()
        val recurring = when (scheduleType) {
            AutomationScheduleType.SILENCE -> {
                require(current.mode == AutomationMode.CHAT) { "沉默触发仅支持聊天模式" }
                require((silenceMinutes ?: 0L) >= 60L) { "聊天沉默触发最短为 1 小时" }
                silenceMinutes
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
        }
        val nextRun = if (scheduleType == AutomationScheduleType.SILENCE) {
            now + requireNotNull(silenceMinutes) * 60_000L
        } else {
            firstRunAtMillis.coerceAtLeast(now)
        }
        val wasPaused = current.status == "paused"
        val updated = current.copy(
            prompt = prompt.trim(),
            nextRunAt = nextRun,
            recurringMinutes = recurring,
            scheduleType = scheduleType,
            scheduleAnchorAt = if (scheduleType == AutomationScheduleType.SILENCE) now else nextRun,
            silenceMinutes = if (scheduleType == AutomationScheduleType.SILENCE) silenceMinutes else null,
            quietHoursEnabled = quietHoursEnabled,
            quietStartHour = quietStartHour,
            quietEndHour = quietEndHour,
            status = if (wasPaused) "paused" else "scheduled",
            lastError = null,
            failureStreak = 0,
        )
        workManager.cancelUniqueWork(workName(id))
        store.upsert(updated)
        if (!wasPaused) {
            if (usesChainedChatScheduling(updated) || updated.recurringMinutes == null) {
                enqueueOneTime(id, nextRun)
            } else {
                enqueuePeriodic(id, requireNotNull(updated.recurringMinutes), nextRun)
            }
        }
        return true
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
        fun localHarnessEngine(): LocalHarnessEngine
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
        if (task.status == "paused" && !manualRun) return Result.success()

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
            val engine = entry.localHarnessEngine()
            val workSessionId = if (task.mode == AutomationMode.WORK) {
                engine.prepareAutomationWorkSession(
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
                AutomationMode.WORK -> engine.runAutomationWork(
                    text = task.prompt,
                    preferredSessionId = workSessionId,
                    recoverInterrupted = recovering,
                )
                AutomationMode.CHAT -> engine.runAutomationChat(
                    instruction = task.prompt,
                    targetSessionId = requireNotNull(task.targetSessionId) {
                        "角色定时互动缺少目标会话"
                    },
                    recoverInterrupted = recovering,
                    recoveryStartedAt = started,
                    quietHoursEnabled = task.quietHoursEnabled && !manualRun,
                    quietStartHour = task.quietStartHour,
                    quietEndHour = task.quietEndHour,
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
            val deferredOneShot = !manualRun &&
                task.recurringMinutes == null &&
                !run.delivered &&
                run.nextRunAtHint != null
            val next = when {
                chained -> nextAnchoredAutomationRun(
                    task = task,
                    afterMillis = finished,
                    suggestedRunAt = run.nextRunAtHint,
                )
                deferredOneShot -> run.nextRunAtHint
                !manualRun && task.recurringMinutes != null ->
                    finished + task.recurringMinutes * 60_000L
                else -> task.nextRunAt
            }
            val receiptStatus = if (run.delivered) "completed" else "skipped"
            val resultText = run.skipReason ?: run.output
            val updated = store.update(id) { current ->
                val nextStatus = when {
                    manualRun -> current.status
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
                task.recurringMinutes?.let { finished + it * 60_000L }
            }
            val updated = store.update(id) { current ->
                val nextFailureStreak = current.failureStreak + 1
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
                task.recurringMinutes?.let { finished + it * 60_000L }
            }
            val updated = store.update(id) { current ->
                val nextFailureStreak = current.failureStreak + 1
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
            id = AUTOMATION_NOTIFICATION_BASE + (task.id.hashCode() and Int.MAX_VALUE) % 10_000,
            title = title,
            text = text,
            sessionId = sessionId,
        )
    }

    companion object {
        private const val AUTOMATION_NOTIFICATION_BASE = 34_000
    }
}

class AutomationPlugin(
    private val scheduler: HarnessAutomationScheduler,
    private val store: AutomationStore,
) : HarnessPlugin {
    override val id: String = "android-automation"

    override suspend fun install(context: HarnessContext) {
        register(
            context,
            name = "schedule_task",
            description = "创建一次性 Android 后台 Harness 任务",
            properties = mapOf(
                "id" to "string",
                "prompt" to "string",
                "delay_minutes" to "integer",
                "run_at_epoch_ms" to "integer",
                "notify" to "boolean",
            ),
            required = setOf("id", "prompt"),
            approval = ToolApprovalPolicy.ALWAYS,
        ) { input ->
            val now = System.currentTimeMillis()
            val runAt = input["run_at_epoch_ms"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: (now + (input["delay_minutes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) * 60_000L)
            val id = input.required("id")
            scheduler.scheduleOnce(
                id = id,
                prompt = input.required("prompt"),
                triggerAtMillis = runAt,
                notify = input["notify"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            )
            "已创建任务 $id，计划时间：$runAt"
        }
        register(
            context,
            name = "schedule_recurring_task",
            description = "创建可跨进程恢复的周期 Harness 任务，Android 最短周期 15 分钟",
            properties = mapOf(
                "id" to "string",
                "prompt" to "string",
                "interval_minutes" to "integer",
                "delay_minutes" to "integer",
                "notify" to "boolean",
            ),
            required = setOf("id", "prompt", "interval_minutes"),
            approval = ToolApprovalPolicy.ALWAYS,
        ) { input ->
            val first = System.currentTimeMillis() +
                (input["delay_minutes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) * 60_000L
            val id = input.required("id")
            scheduler.schedulePeriodic(
                id = id,
                prompt = input.required("prompt"),
                intervalMinutes = input.required("interval_minutes").toLong(),
                firstRunAtMillis = first,
                notify = input["notify"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            )
            "已创建周期任务 $id"
        }
        register(
            context,
            name = "scheduled_task_list",
            description = "列出 Android 后台 Harness 任务及最近结果",
            access = ToolAccess.READ_ONLY,
        ) {
            val tasks = store.list()
            if (tasks.isEmpty()) "暂无后台任务" else tasks.joinToString("\n\n") { task ->
                buildString {
                    appendLine("id=${task.id}")
                    appendLine("status=${task.status}")
                    appendLine("next_run_at=${task.nextRunAt}")
                    appendLine("recurring_minutes=${task.recurringMinutes ?: 0}")
                    task.lastResult?.let { appendLine("last_result=${it.take(1_000)}") }
                    task.lastError?.let { appendLine("last_error=${it.take(1_000)}") }
                }.trimEnd()
            }
        }
        register(
            context,
            name = "cancel_scheduled_task",
            description = "取消并删除 Android 后台 Harness 任务",
            properties = mapOf("id" to "string"),
            required = setOf("id"),
            approval = ToolApprovalPolicy.ALWAYS,
        ) { input ->
            scheduler.cancelTask(input.required("id")).toString()
        }
        context.capabilities.register(
            com.labteto.dshmobile.harness.capability.CapabilityDescriptor("android-scheduler"),
            scheduler,
        )
    }

    override suspend fun uninstall(context: HarnessContext) {
        listOf(
            "schedule_task",
            "schedule_recurring_task",
            "scheduled_task_list",
            "cancel_scheduled_task",
        ).forEach(context.tools::unregister)
        context.capabilities.unregister("android-scheduler")
    }

    private fun register(
        context: HarnessContext,
        name: String,
        description: String,
        properties: Map<String, String> = emptyMap(),
        required: Set<String> = emptySet(),
        access: ToolAccess = ToolAccess.SESSION_WRITE,
        approval: ToolApprovalPolicy = ToolApprovalPolicy.NEVER,
        execute: suspend (JsonObject) -> String,
    ) {
        context.tools.register(
            HarnessTool(
                name = name,
                schema = buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", name)
                        put("description", description)
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                properties.forEach { (key, type) ->
                                    put(key, buildJsonObject { put("type", type) })
                                }
                            })
                            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                            put("additionalProperties", false)
                        })
                    })
                },
                access = access,
                approvalPolicy = approval,
                executor = HarnessToolExecutor { _, input, _ ->
                    ToolResult(execute(input))
                },
            ),
        )
    }

    private fun JsonObject.required(key: String): String =
        this[key]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: error("缺少参数：$key")
}
