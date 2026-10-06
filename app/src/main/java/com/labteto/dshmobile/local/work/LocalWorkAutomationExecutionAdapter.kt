package com.labteto.dshmobile.local.work

import javax.inject.Inject
import javax.inject.Singleton

/** Automation keeps its dedicated boundary while entering Work through the complete Execution Port. */
@Singleton
internal class LocalWorkAutomationExecutionAdapter @Inject constructor(
    private val execution: LocalWorkExecutionPort,
) : LocalWorkAutomationExecutionPort {
    override suspend fun prepareSession(
        text: String,
        preferredSessionId: String?,
    ): String = execution.prepareSession(text, preferredSessionId)

    override suspend fun run(
        text: String,
        preferredSessionId: String?,
        timeoutMillis: Long,
        recoverInterrupted: Boolean,
    ): LocalWorkAutomationResult {
        val result = execution.execute(
            LocalWorkExecutionRequest(
                text = text,
                targetSessionId = preferredSessionId,
                timeoutMillis = timeoutMillis,
                recoverInterrupted = recoverInterrupted,
            ),
        )
        val sessionId = result.sessionId
            ?: throw IllegalStateException(result.detail ?: result.output)
        return LocalWorkAutomationResult(
            sessionId = sessionId,
            output = result.output,
            status = result.status,
            detail = result.detail,
        )
    }
}
