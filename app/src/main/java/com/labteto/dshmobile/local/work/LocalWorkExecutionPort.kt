package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.send.LocalSendResult

/**
 * Work-owned product execution entrypoint.
 *
 * The UI calls this contract directly in Work mode. Its implementation is composed at the app
 * boundary while the stage-3 migration moves the remaining Work turn ownership out of Engine.
 */
internal interface LocalWorkExecutionPort {
    fun send(
        text: String,
        attachments: List<LocalImportedAttachment> = emptyList(),
    ): LocalSendResult

    fun regenerateReply(messageId: String): Boolean
}
