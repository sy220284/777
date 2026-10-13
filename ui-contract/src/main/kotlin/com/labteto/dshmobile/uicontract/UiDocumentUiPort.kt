package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.Flow

data class UiDocumentProvenance(val requestId: String?, val model: String, val usageReported: Boolean)
data class UiDocumentSnapshot(
    val document: UiDocument,
    val provenance: UiDocumentProvenance,
    val actions: Map<String, UiActionAvailability>,
)
data class UiDocumentsSceneSnapshot(
    val sessionId: String?,
    val documents: List<UiDocumentSnapshot>,
    val generating: Boolean,
    val error: String?,
)

data class GenerateUiDocumentCommand(
    val identity: UiSessionIdentity,
    val workMode: Boolean,
    val messageId: String,
    val prompt: String,
    /** Explicit user acknowledgement; documents cannot contain or invoke this command. */
    val acknowledgedAdditionalModelRequest: Boolean,
)

sealed interface UiDocumentReceipt {
    data object AdditionalRequestConsentRequired : UiDocumentReceipt
    data class Published(val documentId: String) : UiDocumentReceipt
    /** The user must confirm the prepared text with the normal composer Send action. */
    data object DraftPrepared : UiDocumentReceipt
    data object NavigationRequested : UiDocumentReceipt
    data class OwnerReceipt(val receipt: UiCommandReceipt) : UiDocumentReceipt
    data class Rejected(val reason: UiCommandRejection) : UiDocumentReceipt
    data class Fallback(val text: String, val reason: String) : UiDocumentReceipt
    data class Failed(val message: String?) : UiDocumentReceipt
}

interface UiDocumentUiPort {
    val snapshots: Flow<UiDocumentsSceneSnapshot>
    fun snapshot(): UiDocumentsSceneSnapshot
    suspend fun generate(command: GenerateUiDocumentCommand): UiDocumentReceipt
    fun submit(identity: UiSessionIdentity, request: UiDocumentActionRequest): UiDocumentReceipt
}
