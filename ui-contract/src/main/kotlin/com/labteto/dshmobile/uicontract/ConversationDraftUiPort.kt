package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.StateFlow

/** Host-owned UI text, shared across renderers; never an execution or delivery fact. */
interface ConversationDraftUiPort {
    val drafts: StateFlow<Map<String, String>>
    fun updateDraft(sessionId: String, text: String)
}
