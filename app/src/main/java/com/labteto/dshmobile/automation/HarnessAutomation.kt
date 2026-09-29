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
    WINDOW,
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

internal fun Int.saturatingIncrement(): Int =
    if (this >= Int.MAX_VALUE) Int.MAX_VALUE else (this + 1).coerceAtLeast(0)

internal fun usesChainedChatScheduling(task: AutomationTask): Boolean =
    task.mode == AutomationMode.CHAT &&
        task.scheduleType in setOf(
            AutomationScheduleType.INTERVAL,
            AutomationScheduleType.DAILY,
            AutomationScheduleType.WEEKLY,
            AutomationScheduleType.SILENCE,
            AutomationScheduleType.WINDOW,
        )

internal fun firstDailyWindowRun(
    afterMillis: Long,
    startMinuteOfDay: Int,
    endMinuteOfDay: Int,
    randomFraction: Double = Random.Default.nextDouble(),
): Long {
    validateWindowMinutes(startMinuteOfDay, endMinuteOfDay)
    require(randomFraction in 0.0..1.0) { "randomFraction must be within 0..1" }

    val reference = Calendar.getInstance().apply { timeInMillis = afterMillis }
    val candidates = (-1..2).map { dayOffset ->
        dailyWindowBounds(
            reference = reference,
            dayOffset = dayOffset,
            startMinuteOfDay = startMinuteOfDay,
            endMinuteOfDay = endMinuteOfDay,
        )
    }

    val window = candidates.firstOrNull { (_, end) -> end > afterMillis }
        ?: error("Unable to resolve next daily window")
    val lower = maxOf(window.first, checkedAutomationAddMillis(afterMillis, 60_000L, "时间窗起点"))
    if (lower >= window.second) {
        return firstDailyWindowRun(
            afterMillis = window.second,
            startMinuteOfDay = startMinuteOfDay,
            endMinuteOfDay = endMinuteOfDay,
            randomFraction = randomFraction,
        )
    }
    return interpolateWindow(lower, window.second, randomFraction)
}

internal fun nextDailyWindowRun(
    previousScheduledAt: Long,
    afterMillis: Long,
    startMinuteOfDay: Int,
    endMinuteOfDay: Int,
    randomFraction: Double = Random.Default.nextDouble(),
): Long {
    validateWindowMinutes(startMinuteOfDay, endMinuteOfDay)
    require(randomFraction in 0.0..1.0) { "randomFraction must be within 0..1" }

    val previous = Calendar.getInstance().apply { timeInMillis = previousScheduledAt }
    val previousMinute = previous.get(Calendar.HOUR_OF_DAY) * 60 + previous.get(Calendar.MINUTE)
    if (startMinuteOfDay > endMinuteOfDay && previousMinute < endMinuteOfDay) {
        previous.add(Calendar.DAY_OF_YEAR, -1)
    }
    previous.add(Calendar.DAY_OF_YEAR, 1)

    repeat(370) {
        val bounds = dailyWindowBounds(
            reference = previous,
            dayOffset = 0,
            startMinuteOfDay = startMinuteOfDay,
            endMinuteOfDay = endMinuteOfDay,
        )
        if (bounds.second > afterMillis) {
            val lower = maxOf(bounds.first, checkedAutomationAddMillis(afterMillis, 60_000L, "时间窗起点"))
            if (lower < bounds.second) {
                return interpolateWindow(lower, bounds.second, randomFraction)
            }
        }
        previous.add(Calendar.DAY_OF_YEAR, 1)
    }
    error("Unable to resolve future daily window")
}

private fun validateWindowMinutes(startMinuteOfDay: Int, endMinuteOfDay: Int) {
    require(startMinuteOfDay in 0 until 24 * 60) { "window start must be within a day" }
    require(endMinuteOfDay in 0 until 24 * 60) { "window end must be within a day" }
    require(startMinuteOfDay != endMinuteOfDay) { "window start and end must differ" }
}

private fun dailyWindowBounds(
    reference: Calendar,
    dayOffset: Int,
    startMinuteOfDay: Int,
    endMinuteOfDay: Int,
): Pair<Long, Long> {
    val start = (reference.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, startMinuteOfDay / 60)
        set(Calendar.MINUTE, startMinuteOfDay % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val end = (start.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, endMinuteOfDay / 60)
        set(Calendar.MINUTE, endMinuteOfDay % 60)
        if (timeInMillis <= start.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
    }
    return start.timeInMillis to end.timeInMillis
}

private fun interpolateWindow(startMillis: Long, endMillis: Long, fraction: Double): Long {
    val clamped = fraction.coerceIn(0.0, 0.999999999)
    return startMillis + ((endMillis - startMillis) * clamped).toLong()
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
    val windowStartMinuteOfDay: Int? = null,
    val windowEndMinuteOfDay: Int? = null,
    val notify: Boolean = true,
    /** Existing tasks decode as WORK; chat interactions explicitly bind to a durable chat session. */
    val mode: AutomationMode = AutomationMode.WORK,
    val targetSessionId: String? = null,
    val actorName: String? = null,
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 23,
    val quietStartMinute: Int = 0,
    val quietEndHour: Int = 7,
    val quietEndMinute: Int = 0,
    val proactiveMinGapMinutes: Long = 6L * 60L,
    val proactiveMaxUnanswered: Int = 2,
    val failureStreak: Int = 0,
    /** Dedicated Work-mode session that owns this task's run history and artifacts. */
    val workSessionId: String? = null,
    val status: String = "scheduled",
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
    val lastError: String? = null,
    val runReceipts: List<AutomationRunReceipt> = emptyList(),
)

internal fun normalizeAutomationTask(task: AutomationTask): AutomationTask {
    val silenceMinutes = when (task.scheduleType) {
        AutomationScheduleType.SILENCE ->
            (task.silenceMinutes ?: task.recurringMinutes ?: 60L).coerceAtLeast(60L)
        else -> task.silenceMinutes?.coerceAtLeast(60L)
    }
    val recurringMinutes = when (task.scheduleType) {
        AutomationScheduleType.SILENCE -> silenceMinutes
        else -> task.recurringMinutes?.coerceAtLeast(15L)
    }
    val windowStart = (
        task.windowStartMinuteOfDay
            ?: (20 * 60).takeIf { task.scheduleType == AutomationScheduleType.WINDOW }
        )?.coerceIn(0, 24 * 60 - 1)
    var windowEnd = (
        task.windowEndMinuteOfDay
            ?: (22 * 60).takeIf { task.scheduleType == AutomationScheduleType.WINDOW }
        )?.coerceIn(0, 24 * 60 - 1)
    if (windowStart != null && windowEnd == windowStart) {
        windowEnd = (windowStart + 120) % (24 * 60)
    }

    return task.copy(
        recurringMinutes = recurringMinutes,
        silenceMinutes = silenceMinutes,
        windowStartMinuteOfDay = windowStart,
        windowEndMinuteOfDay = windowEnd,
        quietStartHour = task.quietStartHour.coerceIn(0, 23),
        quietStartMinute = task.quietStartMinute.coerceIn(0, 59),
        quietEndHour = task.quietEndHour.coerceIn(0, 23),
        quietEndMinute = task.quietEndMinute.coerceIn(0, 59),
        proactiveMinGapMinutes = if (task.mode == AutomationMode.CHAT) {
            task.proactiveMinGapMinutes.coerceAtLeast(60L)
        } else {
            task.proactiveMinGapMinutes.coerceAtLeast(0L)
        },
        proactiveMaxUnanswered = if (task.mode == AutomationMode.CHAT) {
            task.proactiveMaxUnanswered.coerceIn(1, 5)
        } else {
            task.proactiveMaxUnanswered.coerceAtLeast(1)
        },
        failureStreak = task.failureStreak.coerceAtLeast(0),
    )
}

@Serializable
private data class AutomationDocument(
    val version: Int = 1,
    val tasks: List<AutomationTask> = emptyList(),
)

@Singleton
class AutomationStore internal constructor(
    private val file: File,
    private val json: Json,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        json: Json,
    ) : this(File(context.filesDir, "local-harness/automations.json"), json)

    private val backup = File(file.parentFile, file.name + ".bak")

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
        if (!file.isFile) {
            return readValid(backup)?.also { restoreBackup() } ?: AutomationDocument()
        }
        readValid(file)?.let { return it }

        val corrupt = File(file.parentFile, "automations.corrupt-${System.currentTimeMillis()}.json")
        runCatching { file.copyTo(corrupt, overwrite = true) }
        val recovered = readValid(backup) ?: return AutomationDocument()
        restoreBackup()
        return recovered
    }

    private fun readValid(source: File): AutomationDocument? {
        if (!source.isFile) return null
        return runCatching {
            val decoded = json.decodeFromString(AutomationDocument.serializer(), source.readText())
            decoded.copy(tasks = decoded.tasks.map(::normalizeAutomationTask))
        }.getOrNull()
    }

    private fun write(document: AutomationDocument) {
        file.parentFile?.mkdirs()
        val encoded = json.encodeToString(AutomationDocument.serializer(), document)
        readValid(file)?.let { current ->
            atomicWrite(backup, json.encodeToString(AutomationDocument.serializer(), current))
        }
        atomicWrite(file, encoded)
        if (readValid(backup) == null) atomicWrite(backup, encoded)
    }

    private fun restoreBackup() {
        if (!backup.isFile) return
        atomicWrite(file, backup.readText())
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
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
        if (
            task.status in setOf("paused", "waiting_user") &&
            !manualRun
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
            val runAt = parseOptionalAutomationLong(
                input["run_at_epoch_ms"]?.jsonPrimitive?.content,
                "run_at_epoch_ms",
            ) ?: checkedAutomationFutureMillis(
                now,
                parseOptionalAutomationLong(
                    input["delay_minutes"]?.jsonPrimitive?.content,
                    "delay_minutes",
                ) ?: 0L,
                "任务延迟",
            )
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
            val first = checkedAutomationFutureMillis(
                System.currentTimeMillis(),
                parseOptionalAutomationLong(
                    input["delay_minutes"]?.jsonPrimitive?.content,
                    "delay_minutes",
                ) ?: 0L,
                "首次任务延迟",
            )
            val id = input.required("id")
            scheduler.schedulePeriodic(
                id = id,
                prompt = input.required("prompt"),
                intervalMinutes = parseOptionalAutomationLong(
                    input.required("interval_minutes"),
                    "interval_minutes",
                ) ?: error("缺少参数：interval_minutes"),
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
