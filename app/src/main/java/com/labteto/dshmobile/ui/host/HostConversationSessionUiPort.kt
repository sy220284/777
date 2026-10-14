package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.ui.screens.local.LocalConversationReadingState
import com.labteto.dshmobile.ui.screens.local.LocalConversationUiStateStore
import com.labteto.dshmobile.uicontract.ConversationDraftUiPort
import com.labteto.dshmobile.uicontract.SessionUiPort
import com.labteto.dshmobile.uicontract.UiSessionDeletionReceipt
import kotlinx.coroutines.CancellationException

/** Session owns deletion; Host removes only presentation state for Owner-confirmed IDs. */
internal class HostConversationSessionUiPort(
    private val business: SessionUiPort,
    private val drafts: ConversationDraftUiPort,
    private val preferences: LocalConversationUiStateStore,
    private val reading: LocalConversationReadingState,
    private val forms: LocalUiDocumentFormState,
) : SessionUiPort by business {
    override suspend fun delete(sessionIds: Set<String>): UiSessionDeletionReceipt {
        val result = business.delete(sessionIds)
        if (result !is UiSessionDeletionReceipt.Deleted) return result
        return try {
            result.sessionIds.forEach { drafts.updateDraft(it, ""); drafts.clearAttachments(it) }
            preferences.forgetSessions(result.sessionIds)
            reading.forgetSessions(result.sessionIds)
            forms.forgetSessions(result.sessionIds)
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiSessionDeletionReceipt.Failed("会话已删除，界面状态清理未完成：${failure.message}") }
    }
}
