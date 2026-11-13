package com.labteto.dshmobile.local.automation

import com.labteto.dshmobile.local.runtime.LocalExecutionStatus
internal typealias LocalAutomationRunStatus = LocalExecutionStatus

/** Result of one detached automation execution. */
internal data class LocalAutomationRunResult(
    val sessionId: String,
    val output: String,
    val status: LocalAutomationRunStatus = LocalAutomationRunStatus.DELIVERED,
    val detail: String? = null,
    val nextRunAtHint: Long? = null,
    val waitingForUserReply: Boolean = false,
) {
    val delivered: Boolean get() = status == LocalAutomationRunStatus.DELIVERED
    val skipReason: String? get() = detail.takeIf { status == LocalAutomationRunStatus.SKIPPED }
}
