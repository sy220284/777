package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.send.LocalSendResult
import javax.inject.Inject
import javax.inject.Singleton

/** Chat-owned product execution entrypoint. */
@Singleton
internal class LocalChatExecutionCoordinator @Inject constructor(
    private val sendCoordinator: LocalChatSendCoordinator,
    private val turn: LocalChatTurnPort,
) : LocalChatExecutionPort {
    override fun send(
        text: String,
        attachments: List<LocalImportedAttachment>,
    ): LocalSendResult = sendCoordinator.send(text, attachments)

    override fun editAndResendUserMessage(
        messageId: String,
        replacement: String,
    ): LocalChatUserEditResult = turn.editAndResendUserMessage(messageId, replacement)

    override fun regenerateReply(messageId: String): Boolean =
        turn.regenerateReply(messageId)
}
