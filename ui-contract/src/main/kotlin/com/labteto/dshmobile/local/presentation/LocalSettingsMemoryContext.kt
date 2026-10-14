package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.session.LocalConversationMode

data class LocalSettingsMemoryContext(
    val conversationMode: LocalConversationMode,
    val projectId: String?,
    val lineageId: String,
)
