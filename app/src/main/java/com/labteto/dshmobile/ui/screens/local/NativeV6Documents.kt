package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalConversationUiPortComposition
import com.labteto.dshmobile.uicontract.*

/**
 * V6 native conversation owner adapter: documents are business records, never a second UI runtime.
 * All mutations re-enter the current Chat/Work owner with a fresh session identity.
 */
internal class NativeV6Documents(
    private val port: UiDocumentUiPort,
    private val conversations: LocalConversationUiPortComposition,
    private val drafts: NativeV6DocumentDraftPort,
    val forms: UiDocumentFormUiPort,
    val media: MediaUiPort,
) {
    /** The message cards and V6 document drawer share one durable Owner and draft destination. */
    val snapshots = port.snapshots
    /** Single-use handoff: transferring prepared text must never replay on recomposition. */
    fun consumePreparedDraft(sessionId: String): String? = drafts.consumeDraft(sessionId)

    fun submit(snapshot: UiDocumentSnapshot, actionId: String, fields: Map<String, String>, confirmed: Boolean): UiDocumentReceipt {
        val identity = conversations.identitySnapshot()
            ?: return UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
        return port.submit(identity, UiDocumentActionRequest(
            snapshot.document.documentId, snapshot.document.revision,
            snapshot.document.source, actionId, fields, confirmed,
        ))
    }

    suspend fun delete(snapshot: UiDocumentSnapshot): UiDocumentReceipt {
        val identity = conversations.identitySnapshot()
            ?: return UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
        return port.delete(DeleteUiDocumentCommand(
            identity, snapshot.document.documentId, snapshot.document.revision, snapshot.document.source,
        ))
    }

    suspend fun generate(messageId: String, prompt: String): UiDocumentReceipt {
        val identity = conversations.identitySnapshot()
            ?: return UiDocumentReceipt.Rejected(UiCommandRejection.STALE_IDENTITY)
        return port.generate(GenerateUiDocumentCommand(identity,
            conversations.documentFactsSnapshot().let { it.workMode && it.identity == identity }, messageId, prompt,
            acknowledgedAdditionalModelRequest = true))
    }

    suspend fun olderDocuments(sessionId: String, messageIds: Set<String>): List<UiDocumentSnapshot> {
        val identity = conversations.identitySnapshot()
            ?: return emptyList()
        if (identity.sessionId != sessionId) return emptyList()
        return port.documentsForMessages(identity, messageIds)
    }
}
