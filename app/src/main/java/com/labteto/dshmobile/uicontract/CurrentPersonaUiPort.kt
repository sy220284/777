package com.labteto.dshmobile.uicontract

import kotlinx.serialization.Serializable

@Serializable
data class UiCurrentPersonaTarget(val identity: UiSessionIdentity, val personaId: String,
    val revision: Long, val tuningRevision: Long)
data class UiCurrentPersonaSnapshot(val target: UiCurrentPersonaTarget, val draft: UiPersonaDraft, val samplingPosition: Int = 50)
sealed interface UiCurrentPersonaReceipt {
    data class Loaded(val snapshot: UiCurrentPersonaSnapshot) : UiCurrentPersonaReceipt
    data class Suggested(val draft: UiPersonaDraft) : UiCurrentPersonaReceipt
    /** The original Chat Owner committed the active persona, not an archive-only edit. */
    data object Saved : UiCurrentPersonaReceipt
    data object Rejected : UiCurrentPersonaReceipt
    data class Failed(val message: String?) : UiCurrentPersonaReceipt
}
interface CurrentPersonaUiPort {
    fun read(): UiCurrentPersonaReceipt
    suspend fun save(target: UiCurrentPersonaTarget, draft: UiPersonaDraft): UiCurrentPersonaReceipt
    suspend fun suggest(target: UiCurrentPersonaTarget, draft: UiPersonaDraft, description: String): UiCurrentPersonaReceipt
}
