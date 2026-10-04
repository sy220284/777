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

internal fun validateWindowMinutes(startMinuteOfDay: Int, endMinuteOfDay: Int) {
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
    val scheduleGeneration: Long = 0L,
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

@Singleton
class AutomationStore internal constructor(
    private val file: File,
    private val json: Json,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        json: Json,
    ) : this(File(context.filesDir, "local-harness/automations.json"), json)

    private val documents = AutomationDocumentStore(file, json)
    private val taskState = kotlinx.coroutines.flow.MutableStateFlow(list())
    val tasks: kotlinx.coroutines.flow.StateFlow<List<AutomationTask>> = taskState

    @Synchronized
    fun list(): List<AutomationTask> = read().tasks.sortedBy { it.nextRunAt }

    @Synchronized
    fun get(id: String): AutomationTask? = read().tasks.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(task: AutomationTask) {
        val document = read()
        val current = document.tasks.filterNot { it.id == task.id } + normalizeAutomationTask(task)
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

    private fun read(): AutomationDocument =
        documents.read().let { document ->
            document.copy(tasks = document.tasks.map(::normalizeAutomationTask))
        }

    private fun write(document: AutomationDocument) {
        documents.write(document)
        taskState.value = document.tasks.map(::normalizeAutomationTask).sortedBy { it.nextRunAt }
    }

}

