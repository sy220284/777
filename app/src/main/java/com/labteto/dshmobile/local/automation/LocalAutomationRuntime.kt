package com.labteto.dshmobile.local.automation

import com.labteto.dshmobile.local.LocalAutomationRunResult
import com.labteto.dshmobile.local.LocalHarnessEngine
import javax.inject.Inject
import javax.inject.Singleton

/** Automation-only execution boundary shared by WorkManager and the loopback webhook service. */
@Singleton
class LocalAutomationRuntime @Inject constructor(
    private val engine: LocalHarnessEngine,
) {
    internal suspend fun runPrompt(
        text: String,
        timeoutMillis: Long = 5 * 60_000L,
    ): String = engine.runAutomationPrompt(text, timeoutMillis)

    internal suspend fun prepareWorkSession(
        text: String,
        preferredSessionId: String? = null,
    ): String = engine.prepareAutomationWorkSession(text, preferredSessionId)

    internal suspend fun runWork(
        text: String,
        preferredSessionId: String? = null,
        timeoutMillis: Long = 5 * 60_000L,
        recoverInterrupted: Boolean = false,
    ): LocalAutomationRunResult = engine.runAutomationWork(
        text = text,
        preferredSessionId = preferredSessionId,
        timeoutMillis = timeoutMillis,
        recoverInterrupted = recoverInterrupted,
    )

    internal suspend fun runChat(
        instruction: String,
        targetSessionId: String,
        timeoutMillis: Long = 3 * 60_000L,
        recoverInterrupted: Boolean = false,
        recoveryStartedAt: Long? = null,
        quietHoursEnabled: Boolean = false,
        quietStartHour: Int = 23,
        quietStartMinute: Int = 0,
        quietEndHour: Int = 7,
        quietEndMinute: Int = 0,
        proactiveMinGapMinutes: Long = 6L * 60L,
        proactiveMaxUnanswered: Int = 2,
        minimumSilenceMinutes: Long? = null,
        silenceReferenceAt: Long? = null,
        bypassProactivePolicy: Boolean = false,
    ): LocalAutomationRunResult = engine.runAutomationChat(
        instruction = instruction,
        targetSessionId = targetSessionId,
        timeoutMillis = timeoutMillis,
        recoverInterrupted = recoverInterrupted,
        recoveryStartedAt = recoveryStartedAt,
        quietHoursEnabled = quietHoursEnabled,
        quietStartHour = quietStartHour,
        quietStartMinute = quietStartMinute,
        quietEndHour = quietEndHour,
        quietEndMinute = quietEndMinute,
        proactiveMinGapMinutes = proactiveMinGapMinutes,
        proactiveMaxUnanswered = proactiveMaxUnanswered,
        minimumSilenceMinutes = minimumSilenceMinutes,
        silenceReferenceAt = silenceReferenceAt,
        bypassProactivePolicy = bypassProactivePolicy,
    )
}
