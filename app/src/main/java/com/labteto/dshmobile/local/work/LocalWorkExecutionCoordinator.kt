package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.send.prepareLocalSend
import javax.inject.Inject
import javax.inject.Singleton

/** Work-owned product execution entry. */
@Singleton
internal class LocalWorkExecutionCoordinator @Inject constructor(
    private val turn: LocalWorkTurnPort,
) : LocalWorkExecutionPort {
    override fun send(
        text: String,
        attachments: List<LocalImportedAttachment>,
    ): LocalSendResult {
        val prepared = prepareLocalSend(text, attachments) ?: return LocalSendResult.Empty
        return turn.sendPrepared(prepared)
    }

    override fun regenerateReply(messageId: String): Boolean =
        turn.regenerateReply(messageId)
}
