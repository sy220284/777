package com.labteto.dshmobile.local.automation

import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.chat.LocalChatAutomationExecutionPort
import com.labteto.dshmobile.local.work.LocalWorkAutomationExecutionPort
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Automation execution plus the bounded chat snapshot used by the event planner. */
@Singleton
class LocalAutomationRuntime @Inject internal constructor(
    private val chatExecution: LocalChatAutomationExecutionPort,
    private val workExecution: LocalWorkAutomationExecutionPort,
    private val runtimeStateStore: LocalRuntimeStateStore,
) {
    private val runtimeState = runtimeStateStore.state
    internal val planningRevisions = runtimeState.map { it.toAutomationPlanningRevision() }.distinctUntilChanged()


    internal fun planningContext(): AutomationPlanningContext =
        runtimeState.value.toAutomationPlanningContext()

    internal fun planningRevision(): AutomationPlanningRevision =
        runtimeState.value.toAutomationPlanningRevision()

    internal suspend fun runPrompt(text: String, timeoutMillis: Long = 5 * 60_000L): String =
        runWork(text = text, timeoutMillis = timeoutMillis).output

    internal suspend fun <T> withModelRequestResource(block: suspend () -> T): T =
        runtimeStateStore.withModelRequestResource(block)


    internal suspend fun prepareWorkSession(text: String, preferredSessionId: String? = null): String =
        workExecution.prepareSession(text, preferredSessionId)
    internal suspend fun runWork(
        text: String,
        preferredSessionId: String? = null,
        timeoutMillis: Long = 5 * 60_000L,
        recoverInterrupted: Boolean = false,
    ): LocalAutomationRunResult = workExecution.run(
        text = text,
        preferredSessionId = preferredSessionId,
        timeoutMillis = timeoutMillis,
        recoverInterrupted = recoverInterrupted,
    ).let { result ->
        LocalAutomationRunResult(
            sessionId = result.sessionId,
            output = result.output,
        )
    }
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
    ): LocalAutomationRunResult = chatExecution.run(
        instruction, targetSessionId, timeoutMillis, recoverInterrupted, recoveryStartedAt,
        quietHoursEnabled, quietStartHour, quietStartMinute, quietEndHour, quietEndMinute,
        proactiveMinGapMinutes, proactiveMaxUnanswered, minimumSilenceMinutes,
        silenceReferenceAt, bypassProactivePolicy,
    ).let { result ->
        LocalAutomationRunResult(
            sessionId = result.sessionId,
            output = result.output,
            delivered = result.delivered,
            skipReason = result.skipReason,
            nextRunAtHint = result.nextRunAtHint,
            waitingForUserReply = result.waitingForUserReply,
        )
    }
}
