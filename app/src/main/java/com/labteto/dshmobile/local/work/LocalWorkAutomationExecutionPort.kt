package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.runtime.LocalExecutionStatus
internal typealias LocalWorkAutomationStatus = LocalExecutionStatus

/** WorkFeature-owned Automation entrypoint. Automation receives only a bounded execution result. */
internal interface LocalWorkAutomationExecutionPort {
    suspend fun prepareSession(text: String, preferredSessionId: String? = null): String

    suspend fun run(
        text: String,
        preferredSessionId: String? = null,
        timeoutMillis: Long = 5 * 60_000L,
        recoverInterrupted: Boolean = false,
    ): LocalWorkAutomationResult
}

internal data class LocalWorkAutomationResult(
    val sessionId: String,
    val output: String,
    val status: LocalWorkAutomationStatus = LocalWorkAutomationStatus.DELIVERED,
    val detail: String? = null,
)
