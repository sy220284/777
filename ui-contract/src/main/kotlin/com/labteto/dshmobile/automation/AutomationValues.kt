package com.labteto.dshmobile.automation

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Canonical immutable Automation API values; scheduling and normalization remain with Automation. */
object AutomationUiDefaults {
    const val DEFAULT_QUIET_START_HOUR = 23
    const val DEFAULT_QUIET_START_MINUTE = 0
    const val DEFAULT_QUIET_END_HOUR = 7
    const val DEFAULT_QUIET_END_MINUTE = 0
    const val DEFAULT_PROACTIVE_MIN_GAP_MINUTES = 6L * 60L
    const val DEFAULT_PROACTIVE_MAX_UNANSWERED = 2
}

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
    MONTHLY,
    SILENCE,
    WINDOW,
}

/**
 * Persisted scheduling ownership. Null means a pre-upgrade task: retain its original
 * WorkManager strategy until the user explicitly edits it. Do not silently replace
 * an in-flight periodic WorkRequest during application startup.
 */
@Serializable
enum class AutomationDispatchPolicy { PERIODIC, CHAINED }

@Serializable(with = AutomationStatusSerializer::class)
enum class AutomationStatus(val wireValue: String) {
    SCHEDULED("scheduled"),
    RUNNING("running"),
    WAITING_USER("waiting_user"),
    PAUSED("paused"),
    BLOCKED("blocked"),
    FAILED("failed"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    SKIPPED("skipped"),
    QUEUED("queued");

    override fun toString(): String = wireValue
}

object AutomationStatusSerializer : KSerializer<AutomationStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AutomationStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AutomationStatus) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): AutomationStatus {
        val wireValue = decoder.decodeString()
        return AutomationStatus.entries.firstOrNull { it.wireValue == wireValue }
            // A downgraded client must not execute a future state it cannot interpret.
            ?: AutomationStatus.BLOCKED
    }
}

@Serializable
data class AutomationRunReceipt(
    val startedAt: Long,
    val finishedAt: Long,
    val status: AutomationStatus,
    val sessionId: String? = null,
    val resultPreview: String? = null,
    val errorPreview: String? = null,
)


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
    val quietStartHour: Int = AutomationUiDefaults.DEFAULT_QUIET_START_HOUR,
    val quietStartMinute: Int = AutomationUiDefaults.DEFAULT_QUIET_START_MINUTE,
    val quietEndHour: Int = AutomationUiDefaults.DEFAULT_QUIET_END_HOUR,
    val quietEndMinute: Int = AutomationUiDefaults.DEFAULT_QUIET_END_MINUTE,
    val proactiveMinGapMinutes: Long = AutomationUiDefaults.DEFAULT_PROACTIVE_MIN_GAP_MINUTES,
    val proactiveMaxUnanswered: Int = AutomationUiDefaults.DEFAULT_PROACTIVE_MAX_UNANSWERED,
    val failureStreak: Int = 0,
    /** Dedicated Work-mode session that owns this task's run history and artifacts. */
    val workSessionId: String? = null,
    val status: AutomationStatus = AutomationStatus.SCHEDULED,
    val lastRunAt: Long? = null,
    val lastUserActivityAt: Long? = null,
    val lastResult: String? = null,
    val lastError: String? = null,
    val runReceipts: List<AutomationRunReceipt> = emptyList(),
    val dispatchPolicy: AutomationDispatchPolicy? = null,
)

