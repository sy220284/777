package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalConversationUiPortComposition
import com.labteto.dshmobile.local.presentation.LocalUiDocumentUiPortFactory
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * V6 native conversation owner adapter: documents are business records, never a second UI runtime.
 * All mutations re-enter the current Chat/Work owner with a fresh session identity.
 */
internal class NativeV6Documents(
    factory: LocalUiDocumentUiPortFactory,
    private val conversations: LocalConversationUiPortComposition,
    scope: CoroutineScope,
) {
    private val draftStore = object : ConversationDraftUiPort {
        override val drafts = MutableStateFlow<Map<String, String>>(emptyMap())
        override val attachments = MutableStateFlow<Map<String, List<LocalImportedAttachment>>>(emptyMap())
        override fun updateDraft(sessionId: String, text: String) {
            drafts.value = drafts.value + (sessionId to text)
        }
        override fun addAttachment(sessionId: String, attachment: LocalImportedAttachment) {
            attachments.value = attachments.value + (sessionId to (attachments.value[sessionId].orEmpty() + attachment))
        }
        override fun removeAttachment(sessionId: String, relativePath: String) {
            attachments.value = attachments.value + (sessionId to attachments.value[sessionId].orEmpty().filterNot { it.relativePath == relativePath })
        }
        override fun clearAttachments(sessionId: String) {
            attachments.value = attachments.value - sessionId
        }
    }
    private val port = factory.create(scope, draftStore, emptySet()) { }
    val snapshots = port.snapshots
    val preparedDrafts: StateFlow<Map<String, String>> = draftStore.drafts

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
            conversations.work.snapshot().identity == identity, messageId, prompt,
            acknowledgedAdditionalModelRequest = true))
    }

    suspend fun olderDocuments(sessionId: String, messageIds: Set<String>): List<UiDocumentSnapshot> {
        val identity = conversations.identitySnapshot()
            ?: return emptyList()
        if (identity.sessionId != sessionId) return emptyList()
        return port.documentsForMessages(identity, messageIds)
    }
}
