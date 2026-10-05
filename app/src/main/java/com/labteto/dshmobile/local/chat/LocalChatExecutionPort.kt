package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.send.LocalSendResult

/**
 * Chat-owned product execution entrypoint.
 *
 * Chat UI no longer reaches LocalHarnessEngine directly. The concrete coordinator remains hidden
 * behind this Feature contract while stage 3-C/3-D finish moving turn and timeline ownership.
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
}
