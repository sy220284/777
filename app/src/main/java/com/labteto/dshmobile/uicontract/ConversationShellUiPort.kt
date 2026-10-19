package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.StateFlow

/** Host-owned presentation preferences. These commands do not rewrite Session history. */
sealed interface ConversationShellUiCommand {
    val sessionId: String
    data class Rename(override val sessionId: String, val title: String) : ConversationShellUiCommand
    data class SetPinned(override val sessionId: String, val pinned: Boolean) : ConversationShellUiCommand
}
interface ConversationShellUiPort {
    val pinnedSessionIds: StateFlow<Set<String>>
    val sessionTitleOverrides: StateFlow<Map<String, String>>
    fun submit(command: ConversationShellUiCommand): UiCommandReceipt
}
