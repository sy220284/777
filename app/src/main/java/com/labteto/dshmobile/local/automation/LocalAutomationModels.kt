package com.labteto.dshmobile.local.automation



/** Result of one detached Work-mode automation run. */
data class LocalAutomationRunResult(
    val sessionId: String,
    val output: String,
    val delivered: Boolean = true,
    val skipReason: String? = null,
    val nextRunAtHint: Long? = null,
    val waitingForUserReply: Boolean = false,
)
