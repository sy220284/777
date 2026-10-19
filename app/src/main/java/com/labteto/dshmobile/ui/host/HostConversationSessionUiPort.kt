package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uicontract.ConversationDraftUiPort
import com.labteto.dshmobile.uicontract.SessionUiPort
import com.labteto.dshmobile.uicontract.UiSessionDeletionReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/** A new Work must be issued by the Session Owner and match the exact accepted handoff. */
internal fun matchesAcceptedWorkHandoff(
    command: SessionUiCommand.Create, snapshot: UiSessionsSnapshot, storedSummary: String?,
): Boolean = !snapshot.loading && snapshot.identity != null &&
    snapshot.identity.sessionId != command.identity?.sessionId &&
    snapshot.usageMode == com.labteto.dshmobile.local.LocalUsageMode.WORK &&
    snapshot.usageMode == command.usageMode &&
    command.handoffSummary != null && command.handoffSummary == storedSummary &&
    command.handoffAttachments.isNotEmpty()

/** Session owns deletion; Host removes only presentation state for Owner-confirmed IDs. */
internal class HostConversationSessionUiPort(
    private val business: SessionUiPort,
    private val drafts: ConversationDraftUiPort,
    private val preferences: LocalConversationUiStateStore,
    private val reading: LocalConversationReadingState,
    private val forms: LocalUiDocumentFormState,
    private val onConversationRequested: (SessionUiCommand) -> Unit = {},
) : SessionUiPort by business {
    // The new Work identity is issued asynchronously. Never guess its ID from
    // the source chat or copy selected attachments into an arbitrary session.
    private data class PendingHandoff(
        val command: SessionUiCommand.Create,
        val acceptedAt: Long,
    )
    private var pendingHandoff: PendingHandoff? = null

    override val snapshots: Flow<UiSessionsSnapshot> =
        business.snapshots.onEach { applyAcceptedHandoff(it) }

    override fun snapshot(): UiSessionsSnapshot =
        business.snapshot().also { applyAcceptedHandoff(it) }

    private fun applyAcceptedHandoff(snapshot: UiSessionsSnapshot) {
        val pending = synchronized(this) { pendingHandoff } ?: return
        if (System.currentTimeMillis() - pending.acceptedAt > 120_000) {
            synchronized(this) { if (pendingHandoff === pending) pendingHandoff = null }
            return
        }
        val id = snapshot.identity ?: return
        val request = pending.command
        if (!matchesAcceptedWorkHandoff(request, snapshot, business.handoffSummary(id))) return
        val claimed = synchronized(this) {
            if (pendingHandoff === pending) { pendingHandoff = null; true } else false
        }
        if (!claimed) return
        // All attachments were explicitly selected and were already admitted
        // into the source workspace. Only the newly activated Work draft gets them.
        request.handoffAttachments.distinctBy { it.relativePath }.forEach { attachment ->
            drafts.addAttachment(id.sessionId, attachment)
        }
    }

    override fun submit(command: SessionUiCommand): UiCommandReceipt {
        val result = business.submit(command)
        if (command is SessionUiCommand.Create &&
            command.handoffAttachments.isNotEmpty() &&
            command.handoffSummary != null &&
            (result == UiCommandReceipt.Accepted || result == UiCommandReceipt.Queued)) {
            synchronized(this) { pendingHandoff = PendingHandoff(command, System.currentTimeMillis()) }
            applyAcceptedHandoff(business.snapshot())
        }
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
