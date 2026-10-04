package com.labteto.dshmobile.local.automation

import com.labteto.dshmobile.local.LocalAutomationRunResult
import com.labteto.dshmobile.local.LocalHarnessEngine
import javax.inject.Inject
import javax.inject.Singleton

/** Automation execution plus the bounded chat snapshot used by the event planner. */
@Singleton
class LocalAutomationRuntime @Inject constructor(
    private val engine: LocalHarnessEngine,
) {
    private val runtimeState = engine.state

    internal fun planningContext(): AutomationPlanningContext =
        runtimeState.value.toAutomationPlanningContext()

    internal fun planningRevision(): AutomationPlanningRevision =
        runtimeState.value.toAutomationPlanningRevision()

    internal suspend fun runPrompt(text: String, timeoutMillis: Long = 5 * 60_000L): String =
        engine.runAutomationPrompt(text, timeoutMillis)

    internal suspend fun <T> withModelRequestResource(block: suspend () -> T): T =
        engine.withAutomationModelRequestResource(block)

    internal suspend fun prepareWorkSession(text: String, preferredSessionId: String? = null): String =
        engine.prepareAutomationWorkSession(text, preferredSessionId)

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
        instruction, targetSessionId, timeoutMillis, recoverInterrupted, recoveryStartedAt,
        quietHoursEnabled, quietStartHour, quietStartMinute, quietEndHour, quietEndMinute,
        proactiveMinGapMinutes, proactiveMaxUnanswered, minimumSilenceMinutes,
        silenceReferenceAt, bypassProactivePolicy,
    )
}
