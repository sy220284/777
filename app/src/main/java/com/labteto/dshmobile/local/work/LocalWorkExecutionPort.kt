package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.send.LocalSendResult

/**
 * Work-owned product execution entrypoint.
 *
 * The UI calls this contract directly in Work mode. WorkFeature owns the implementation; only the
 * lower-level turn bridge remains temporarily composed at the app boundary during stage-3 migration.
 */
internal interface LocalWorkExecutionPort {
    fun send(
        text: String,
        attachments: List<LocalImportedAttachment> = emptyList(),
    ): LocalSendResult

    fun regenerateReply(messageId: String): Boolean
}
