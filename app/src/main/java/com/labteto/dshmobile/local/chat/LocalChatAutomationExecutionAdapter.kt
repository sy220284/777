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
        policy: LocalChatAutomationPolicy,
    ): LocalChatAutomationResult {
        val result = execution.execute(
            LocalChatExecutionRequest(
                instruction = instruction,
                targetSessionId = targetSessionId,
                timeoutMillis = timeoutMillis,
                recoverInterrupted = recoverInterrupted,
                recoveryStartedAt = recoveryStartedAt,
                automationPolicy = policy,
            ),
        )
        val sessionId = result.sessionId
            ?: throw IllegalStateException(result.detail ?: result.output)
        return LocalChatAutomationResult(
            sessionId = sessionId,
            output = result.output,
            status = result.status,
            detail = result.detail,
            nextRunAtHint = result.nextRunAtHint,
            waitingForUserReply = result.waitingForUserReply,
        )
    }
}
