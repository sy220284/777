package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.send.LocalSendResult

internal enum class LocalChatExecutionStatus {
    DELIVERED,
    SKIPPED,
    BLOCKED,
    CANCELLED,
    FAILED,
}

internal data class LocalChatExecutionRequest(
    val instruction: String,
    val targetSessionId: String,
    val timeoutMillis: Long = 3 * 60_000L,
    val recoverInterrupted: Boolean = false,
    val recoveryStartedAt: Long? = null,
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 23,
    val quietStartMinute: Int = 0,
    val quietEndHour: Int = 7,
    val quietEndMinute: Int = 0,
    val proactiveMinGapMinutes: Long = 6L * 60L,
    val proactiveMaxUnanswered: Int = 2,
    val minimumSilenceMinutes: Long? = null,
    val silenceReferenceAt: Long? = null,
    val bypassProactivePolicy: Boolean = false,
)

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
