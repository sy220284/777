package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.StateFlow

/** Host-owned UI text, shared across renderers; never an execution or delivery fact. */
interface ConversationDraftUiPort {
    val drafts: StateFlow<Map<String, String>>
    val attachments: StateFlow<Map<String, List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment>>>
    fun addAttachment(sessionId: String, attachment: com.labteto.dshmobile.local.attachment.LocalImportedAttachment)
    fun removeAttachment(sessionId: String, relativePath: String)
    fun clearAttachments(sessionId: String)
    fun updateDraft(sessionId: String, text: String)
}
