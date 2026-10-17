package com.labteto.dshmobile.uicontract

/**
 * Undo is a Chat mutation, bound to the live activation and exact notice
 * issued by the original persona correction Owner. UI never edits persona
 * storage or manufactures an alternative correction notice.
 */
data class UiPersonaCorrectionUndo(
    val identity: UiSessionIdentity,
    val noticeId: Long,
    val personaId: String,
    val correction: String,
)

sealed interface UiPersonaCorrectionReceipt {
    data object Undone : UiPersonaCorrectionReceipt
    data object Stale : UiPersonaCorrectionReceipt
    data class Failed(val reason: String?) : UiPersonaCorrectionReceipt
}

interface PersonaCorrectionUiPort {
    suspend fun undo(command: UiPersonaCorrectionUndo): UiPersonaCorrectionReceipt
}
