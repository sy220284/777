package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.Flow

/** UI-facing, provider-neutral references. Selection is a preference, never a permission grant. */
enum class UiInvocationKind { CONNECTOR, SKILL }
data class UiInvocationRef(val kind: UiInvocationKind, val id: String)
data class UiInvocationOption(
    val ref: UiInvocationRef,
    val label: String,
    val description: String,
    val available: Boolean = true,
)

/** The Owner persists selection; any UI pack may render the same immutable state. */
interface ComposerInvocationUiPort {
    val pinned: Flow<List<UiInvocationRef>>
    suspend fun options(): List<UiInvocationOption>
    fun toggle(ref: UiInvocationRef): UiCommandReceipt
}
