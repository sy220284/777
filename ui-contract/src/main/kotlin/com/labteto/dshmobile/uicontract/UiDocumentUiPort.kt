package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.Flow

enum class UiDocumentOrigin { MODEL, LOCAL, UNKNOWN }
data class UiDocumentProvenance(val requestId: String?, val model: String, val usageReported: Boolean,
    val origin: UiDocumentOrigin = UiDocumentOrigin.MODEL)
data class UiDocumentSnapshot(
    val document: UiDocument,
    val provenance: UiDocumentProvenance,
    val actions: Map<String, UiActionAvailability>,
    val bindings: Map<UiDocumentBinding, UiDocumentBoundValue> = emptyMap(),
    val recoveryNotice: String? = null,
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

/** A user-requested text edit; never created or executed by document-defined actions. */
data class EditUiDocumentTextCommand(
    val identity: UiSessionIdentity,
    val documentId: String,
    val revision: Long,
    val source: UiDocumentSource,
    val textNodeId: String,
    val replacementText: String,
)

/** User-initiated edit of one table display cell; never model-authored and never a tool call. */
data class EditUiDocumentTableCellCommand(
    val identity: UiSessionIdentity,
    val documentId: String,
    val revision: Long,
    val source: UiDocumentSource,
    val tableNodeId: String,
    val rowIndex: Int,
    val columnIndex: Int,
    val replacementText: String,
)

/** User correction of one existing bar/line chart value; no new chart or action authority. */
data class EditUiDocumentChartPointCommand(
    val identity: UiSessionIdentity,
    val documentId: String,
    val revision: Long,
    val source: UiDocumentSource,
    val chartNodeId: String,
    val pointIndex: Int,
    val value: Double,
)

/** Closed user intent; documents themselves cannot embed or invoke deletion. */
data class DeleteUiDocumentCommand(
    val identity: UiSessionIdentity,
    val documentId: String,
    val revision: Long,
    val source: UiDocumentSource,
)

sealed interface UiDocumentReceipt {
    data object AdditionalRequestConsentRequired : UiDocumentReceipt
    data class Published(val documentId: String) : UiDocumentReceipt
    /** An Owner-persisted deletion event, not merely permission to attempt deletion. */
    data object Deleted : UiDocumentReceipt
    /** The user must confirm the prepared text with the normal composer Send action. */
    data object DraftPrepared : UiDocumentReceipt
    data object NavigationRequested : UiDocumentReceipt
    data class OwnerReceipt(val receipt: UiCommandReceipt) : UiDocumentReceipt
    data class Rejected(val reason: UiCommandRejection) : UiDocumentReceipt
    data class InvalidFields(val fieldIds: Set<String>) : UiDocumentReceipt
    data class Fallback(val text: String, val reason: String) : UiDocumentReceipt
    data class Failed(val message: String?) : UiDocumentReceipt
}

interface UiDocumentUiPort {
    val snapshots: Flow<UiDocumentsSceneSnapshot>
    fun snapshot(): UiDocumentsSceneSnapshot
    suspend fun generateLocal(command: GenerateLocalUiDocumentCommand): UiDocumentReceipt
    suspend fun generate(command: GenerateUiDocumentCommand): UiDocumentReceipt
    suspend fun delete(command: DeleteUiDocumentCommand): UiDocumentReceipt
    /** Exactly one durable tombstone for 2–32 explicit user-selected documents. */
    suspend fun deleteBatch(commands: List<DeleteUiDocumentCommand>): UiDocumentReceipt
    suspend fun replaceText(command: EditUiDocumentTextCommand): UiDocumentReceipt =
        UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
    suspend fun replaceTableCell(command: EditUiDocumentTableCellCommand): UiDocumentReceipt =
        UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
    suspend fun replaceChartPoint(command: EditUiDocumentChartPointCommand): UiDocumentReceipt =
        UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED)
    fun submit(identity: UiSessionIdentity, request: UiDocumentActionRequest): UiDocumentReceipt
}
