package com.labteto.dshmobile.local

internal enum class LocalSubagentStatus {
    COMPLETED,
    STEP_LIMIT,
    CANCELLED,
    FAILED,
}

internal data class LocalSubagentResult(
    val status: LocalSubagentStatus,
    val output: String,
    val errorCode: String? = null,
    val retryable: Boolean = false,
) {
    val succeeded: Boolean get() = status == LocalSubagentStatus.COMPLETED
}

internal class LocalSubagentExecutionException(
    val errorCode: String?,
    val retryable: Boolean,
    message: String,
) : IllegalStateException(message)

internal fun LocalSubagentResult.requireCompletedOutput(): String {
    if (!succeeded) throw LocalSubagentExecutionException(errorCode, retryable, output)
    return output
}
