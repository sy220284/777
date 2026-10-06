package com.labteto.dshmobile.local.chat

import javax.inject.Inject
import javax.inject.Singleton

/** Automation keeps its dedicated boundary while entering Chat through the complete Execution Port. */
@Singleton
internal class LocalChatAutomationExecutionAdapter @Inject constructor(
    private val execution: LocalChatExecutionPort,
) : LocalChatAutomationExecutionPort {
    override suspend fun run(
        instruction: String,
        targetSessionId: String,
        timeoutMillis: Long,
        recoverInterrupted: Boolean,
        recoveryStartedAt: Long?,
        quietHoursEnabled: Boolean,
        quietStartHour: Int,
        quietStartMinute: Int,
        quietEndHour: Int,
        quietEndMinute: Int,
        proactiveMinGapMinutes: Long,
        proactiveMaxUnanswered: Int,
        minimumSilenceMinutes: Long?,
        silenceReferenceAt: Long?,
        bypassProactivePolicy: Boolean,
    ): LocalChatAutomationResult {
        val result = execution.execute(
            LocalChatExecutionRequest(
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
            ),
        )
        val sessionId = result.sessionId
            ?: throw IllegalStateException(result.detail ?: result.output)
        return LocalChatAutomationResult(
            sessionId = sessionId,
            output = result.output,
            status = LocalChatAutomationStatus.valueOf(result.status.name),
            detail = result.detail,
            nextRunAtHint = result.nextRunAtHint,
            waitingForUserReply = result.waitingForUserReply,
        )
    }
}
