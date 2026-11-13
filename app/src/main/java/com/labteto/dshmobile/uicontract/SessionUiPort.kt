package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalSessionSummary
import kotlinx.coroutines.flow.Flow

data class UiPendingSession(val sessionId: String, val usageMode: LocalUsageMode)

/** Durable history and private unfinished-editor identities are independent collections. */
data class UiSessionsSnapshot(val identity: UiSessionIdentity?, val usageMode: LocalUsageMode,
    val loading: Boolean, val sessions: List<LocalSessionSummary>, val error: String?,
    val pendingSessions: List<UiPendingSession> = emptyList())
sealed interface SessionUiCommand {
    /** Navigation has an explicit target and does not retarget a domain mutation. */
    data class Open(val sessionId: String) : SessionUiCommand
    /** A drawer-mode switch need not request conversation navigation. */
    data class SelectMode(val mode: LocalUsageMode, val openConversation: Boolean = true) : SessionUiCommand
    data class Create(val identity: UiSessionIdentity?, val mode: LocalConversationMode,
        val usageMode: LocalUsageMode, val handoffSummary: String? = null,
        val handoffAttachments: List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment> = emptyList()) : SessionUiCommand
}
sealed interface UiSessionDeletionReceipt {
    data class Deleted(val sessionIds: Set<String>) : UiSessionDeletionReceipt
    /** Cleanup may have partially committed. Refresh the real catalog before another attempt. */
    data class Failed(val message: String?) : UiSessionDeletionReceipt
}
interface SessionUiPort {
    val snapshots: Flow<UiSessionsSnapshot>
    fun snapshot(): UiSessionsSnapshot
    fun submit(command: SessionUiCommand): UiCommandReceipt
    fun handoffSummary(identity: UiSessionIdentity): String?
    suspend fun delete(sessionIds: Set<String>): UiSessionDeletionReceipt
}
