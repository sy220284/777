package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.*
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
    private val onConversationRequested: (SessionUiCommand) -> Unit = {},
) : SessionUiPort by business {
    override fun submit(command: SessionUiCommand): UiCommandReceipt {
        val result = business.submit(command)
        if ((result == UiCommandReceipt.Accepted || result == UiCommandReceipt.Queued) &&
            (command !is SessionUiCommand.SelectMode || command.openConversation))
            onConversationRequested(command)
        return result
    }
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

/** A queued request becomes navigable only after its requested Session projection is active. */
internal fun activatedConversationScene(command: SessionUiCommand?, snapshot: UiSessionsSnapshot): String? {
    val identity = snapshot.identity ?: return null
    if (snapshot.loading || command == null) return null
    if (command is SessionUiCommand.SelectMode && !command.openConversation) return null
    val matches = when (command) {
        is SessionUiCommand.Open -> identity.sessionId == command.sessionId
        is SessionUiCommand.Create -> identity.sessionId != command.identity?.sessionId && snapshot.usageMode == command.usageMode
        is SessionUiCommand.SelectMode -> snapshot.usageMode == command.mode
    }
    return if (!matches) null else if (snapshot.usageMode == com.labteto.dshmobile.local.LocalUsageMode.WORK)
        "conversation.work" else "conversation.chat"
}
