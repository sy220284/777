package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.*

/** Capture both text and attachments before admission, then clean only that unchanged draft. */
fun sendZhaoxiDraft(drafts: ConversationDraftUiPort, identity: UiSessionIdentity,
    submit: (String, List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment>) -> UiCommandReceipt): UiCommandReceipt {
    val text = drafts.drafts.value[identity.sessionId].orEmpty()
    val attachments = drafts.attachments.value[identity.sessionId].orEmpty()
    val result = submit(text, attachments)
    if (result == UiCommandReceipt.Accepted || result == UiCommandReceipt.Queued) {
        if (drafts.drafts.value[identity.sessionId].orEmpty() == text) drafts.updateDraft(identity.sessionId, "")
        attachments.forEach { drafts.removeAttachment(identity.sessionId, it.relativePath) }
    }
    return result
}
