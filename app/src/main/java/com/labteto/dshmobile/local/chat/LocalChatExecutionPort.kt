package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.runtime.LocalExecutionStatus
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.send.LocalSendResult

internal typealias LocalChatExecutionStatus = LocalExecutionStatus

internal data class LocalChatExecutionRequest(
    val instruction: String,
    val targetSessionId: String,
    val timeoutMillis: Long = 3 * 60_000L,
    val recoverInterrupted: Boolean = false,
    val recoveryStartedAt: Long? = null,
    val automationPolicy: LocalChatAutomationPolicy = LocalChatAutomationPolicy(),
) {
    val quietHoursEnabled: Boolean get() = automationPolicy.quietHoursEnabled
    val quietStartHour: Int get() = automationPolicy.quietStartHour
    val quietStartMinute: Int get() = automationPolicy.quietStartMinute
    val quietEndHour: Int get() = automationPolicy.quietEndHour
    val quietEndMinute: Int get() = automationPolicy.quietEndMinute
    val proactiveMinGapMinutes: Long get() = automationPolicy.proactiveMinGapMinutes
    val proactiveMaxUnanswered: Int get() = automationPolicy.proactiveMaxUnanswered
    val minimumSilenceMinutes: Long? get() = automationPolicy.minimumSilenceMinutes
    val silenceReferenceAt: Long? get() = automationPolicy.silenceReferenceAt
    val bypassProactivePolicy: Boolean get() = automationPolicy.bypassProactivePolicy
}

internal data class LocalChatExecutionResult(
    val sessionId: String?,
    val output: String,
    val status: LocalChatExecutionStatus,
    val detail: String? = null,
    val nextRunAtHint: Long? = null,
    val waitingForUserReply: Boolean = false,
)

/**
 * Chat-owned product execution entrypoint.
 *
 * Visible Chat operations and explicit target-Session execution share this contract. Detached
 * callers receive structured terminal state without gaining access to persona stores or runners.
 */
internal interface LocalChatExecutionPort {
    fun send(
        text: String,
        attachments: List<LocalImportedAttachment> = emptyList(),
    ): LocalSendResult

    fun editAndResendUserMessage(
        messageId: String,
        replacement: String,
    ): LocalChatUserEditResult

    fun regenerateReply(messageId: String): Boolean

    suspend fun execute(request: LocalChatExecutionRequest): LocalChatExecutionResult

    fun cancel(sessionId: String): Boolean

    suspend fun cancelAndJoin(sessionId: String): Boolean
}
