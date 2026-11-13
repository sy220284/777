package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.Flow

data class UiChatReplySuggestion(val label: String, val text: String, val style: String, val bold: Boolean)
data class UiReplySuggestionsSnapshot(val identity: UiSessionIdentity?, val values: List<UiChatReplySuggestion>)

/** Existing Chat post-turn suggestion owner; no independent model invocation surface. */
interface ReplySuggestionsUiPort {
    val snapshots: Flow<UiReplySuggestionsSnapshot>
    suspend fun generate(identity: UiSessionIdentity): UiCommandReceipt
}
