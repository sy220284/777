package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.runtime.LocalExecutionStatus
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.send.LocalSendResult

internal typealias LocalWorkExecutionStatus = LocalExecutionStatus

internal data class LocalWorkExecutionRequest(
    val text: String,
    val targetSessionId: String? = null,
    val timeoutMillis: Long = 5 * 60_000L,
    val recoverInterrupted: Boolean = false,
)

internal data class LocalWorkExecutionResult(
    val sessionId: String?,
    val output: String,
    val status: LocalWorkExecutionStatus,
    val detail: String? = null,
)

/**
 * Work-owned product execution entrypoint.
 *
 * Visible UI sends and explicit target-Session execution share this contract. Callers receive a
 * structured terminal result and never obtain Work binding, runner or writable aggregate state.
 */
internal interface LocalWorkExecutionPort {
    fun send(
        text: String,
        attachments: List<LocalImportedAttachment> = emptyList(),
    ): LocalSendResult

    fun sendWithTeam(
        text: String,
        attachments: List<LocalImportedAttachment> = emptyList(),
    ): LocalSendResult = send(text, attachments)

    fun regenerateReply(messageId: String): Boolean

    suspend fun prepareSession(
        text: String,
        preferredSessionId: String? = null,
    ): String

    suspend fun execute(request: LocalWorkExecutionRequest): LocalWorkExecutionResult

    fun cancel(sessionId: String): Boolean

    suspend fun cancelAndJoin(sessionId: String): Boolean
}
