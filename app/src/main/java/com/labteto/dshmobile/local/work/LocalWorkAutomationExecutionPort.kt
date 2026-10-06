package com.labteto.dshmobile.local.work

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
)
