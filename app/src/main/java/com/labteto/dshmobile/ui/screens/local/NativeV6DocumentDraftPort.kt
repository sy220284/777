package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.uicontract.ConversationDraftUiPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** V6 composer draft handoff, separate from the Chat/Work execution owner. */
internal class NativeV6DocumentDraftPort : ConversationDraftUiPort {
    private val textState = MutableStateFlow<Map<String, String>>(emptyMap())
    private val attachmentState = MutableStateFlow<Map<String, List<LocalImportedAttachment>>>(emptyMap())
    override val drafts = textState.asStateFlow()
    override val attachments = attachmentState.asStateFlow()

    override fun updateDraft(sessionId: String, text: String) {
        textState.value = if (text.isBlank()) textState.value - sessionId
            else textState.value + (sessionId to text)
    }
    /** A prepared draft is a one-shot handoff. Never replay it after editing or switching sessions. */
    fun consumeDraft(sessionId: String): String? {
        val text = textState.value[sessionId]?.takeIf(String::isNotBlank) ?: return null
        updateDraft(sessionId, "")
        return text
    }
    override fun addAttachment(sessionId: String, attachment: LocalImportedAttachment) {
        attachmentState.value = attachmentState.value + (
            sessionId to (attachmentState.value[sessionId].orEmpty() + attachment)
        )
    }
    override fun removeAttachment(sessionId: String, relativePath: String) {
        attachmentState.value = attachmentState.value + (
            sessionId to attachmentState.value[sessionId].orEmpty().filterNot { it.relativePath == relativePath }
        )
    }
    override fun clearAttachments(sessionId: String) {
        attachmentState.value = attachmentState.value - sessionId
    }
}
