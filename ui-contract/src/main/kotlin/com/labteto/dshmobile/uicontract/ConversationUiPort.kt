package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.Flow

const val UI_CONTRACT_VERSION = 1

/** Opaque owner-issued activation identity; UI cannot derive execution authority from this value. */
@kotlinx.serialization.Serializable
data class UiSessionIdentity(
    val sessionId: String,
    val activationId: String,
    val contextGeneration: Long,
    val profileId: String?,
    val projectId: String?,
) {
    init { require(sessionId.isNotBlank() && activationId.isNotBlank()) }
}

enum class UiSceneStatus { LOADING, EMPTY, CONTENT, ERROR }
enum class UiExecutionPhase { IDLE, RUNNING, WAITING_FOR_USER }

data class UiActionAvailability(val available: Boolean, val reason: UiCommandRejection? = null)
enum class UiCommandRejection {
    EMPTY_INPUT, LOADING, UNCONFIGURED, STALE_IDENTITY, SESSION_TRANSITION,
    QUEUE_FULL, QUEUE_UNAVAILABLE, APPROVAL_EXPIRED, QUESTION_EXPIRED, UNSUPPORTED,
}

sealed interface UiCommandReceipt {
    /** Accepted is admission, not execution completion or verification. */
    data object Accepted : UiCommandReceipt
    data object Queued : UiCommandReceipt
    data class Rejected(val reason: UiCommandRejection, val limit: Int? = null) : UiCommandReceipt
    data class Failed(val message: String) : UiCommandReceipt
    /** Owner declined an operation without exposing a more specific cause. */
    data object NotAdmitted : UiCommandReceipt
    data class MessageEdited(val result: UiMessageEditOutcome) : UiCommandReceipt
    data object OutcomeUnknown : UiCommandReceipt
}

/** All original Owner outcomes are preserved, but no Session domain class crosses the UI API. */
enum class UiMessageEditOutcome {
    SENT,
    COMMITTED_NOT_STARTED,
    BUSY,
    UNAVAILABLE,
    WRONG_MODE,
    UNCONFIGURED,
    HISTORY_UNAVAILABLE,
    MESSAGE_MISSING,
    EMPTY,
    UNCHANGED,
    FAILED,
}
enum class UiMessageRole { USER, ASSISTANT, SYSTEM, TOOL }
data class UiMessageBranch(val index: Int, val count: Int)
/** Transient owner-scoped model text. Durable transcript replaces it when committed. */
data class UiStreamingReplyPreview(val requestId: String, val text: String)
data class UiMessageSnapshot(val id: String, val role: UiMessageRole, val text: String, val createdAt: Long,
    val editableText: String? = null, val branch: UiMessageBranch? = null, val proactive: Boolean = false,
    val progressive: Boolean = false)

/** Chat-owned read-only group facts; there is no implicit group administration command. */
data class UiChatGroupSummary(val memberNames: List<String>)

data class ChatSceneSnapshot(
    val identity: UiSessionIdentity?,
    val status: UiSceneStatus,
    val phase: UiExecutionPhase,
    val messages: List<UiMessageSnapshot>,
    val queuedInputCount: Int,
    val send: UiActionAvailability,
    val error: String?,
    val group: UiChatGroupSummary? = null,
    val livePreview: UiStreamingReplyPreview? = null,
)

sealed interface ChatUiCommand {
    data class SendMessage(val identity: UiSessionIdentity, val text: String, val attachments: List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment>) : ChatUiCommand
    data class SelectVariant(val identity: UiSessionIdentity, val messageId: String, val targetIndex: Int) : ChatUiCommand
    data class EditUserMessage(val identity: UiSessionIdentity, val messageId: String, val replacement: String) : ChatUiCommand
    data class RegenerateReply(val identity: UiSessionIdentity, val messageId: String) : ChatUiCommand
    data class Stop(val identity: UiSessionIdentity) : ChatUiCommand
    data class SendText(val identity: UiSessionIdentity, val text: String) : ChatUiCommand
}

interface ChatUiPort {
    val snapshots: Flow<ChatSceneSnapshot>
    fun snapshot(): ChatSceneSnapshot
    fun submit(command: ChatUiCommand): UiCommandReceipt
}

/** An approval is an existing owner-issued waiter, never a model-created permission. */
enum class UiApprovalImpact { LOW, MEDIUM, HIGH, CRITICAL }
data class UiApprovalSnapshot(
    val pendingId: String,
    val toolName: String,
    val summary: String,
    val arguments: String,
    val impact: UiApprovalImpact,
    val canApproveDeviceTurn: Boolean = false,
)

data class UiQuestionSnapshot(val pendingId: String, val question: String, val options: List<String>)

data class WorkConversationSceneSnapshot(
    val identity: UiSessionIdentity?,
    val status: UiSceneStatus,
    val phase: UiExecutionPhase,
    val messages: List<UiMessageSnapshot>,
    val queuedInputCount: Int,
    val send: UiActionAvailability,
    val approval: UiApprovalSnapshot?,
    val error: String?,
    val question: UiQuestionSnapshot? = null,
    val livePreview: UiStreamingReplyPreview? = null,
)

sealed interface WorkConversationUiCommand {
    /** Null is an explicit cancellation answer, not an empty affirmative response. */
    data class AnswerQuestion(val identity: UiSessionIdentity, val pendingId: String, val answer: String?) : WorkConversationUiCommand
    data class SendMessage(val identity: UiSessionIdentity, val text: String, val attachments: List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment>) : WorkConversationUiCommand
    data class EditUserMessage(val identity: UiSessionIdentity, val messageId: String, val replacement: String) : WorkConversationUiCommand
    data class RegenerateReply(val identity: UiSessionIdentity, val messageId: String) : WorkConversationUiCommand
    data class SendWithTeam(val identity: UiSessionIdentity, val text: String, val attachments: List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment>) : WorkConversationUiCommand
    data class Stop(val identity: UiSessionIdentity) : WorkConversationUiCommand
    data class SendText(val identity: UiSessionIdentity, val text: String) : WorkConversationUiCommand
    data class AnswerApproval(
        val identity: UiSessionIdentity,
        val pendingId: String,
        val approved: Boolean,
    ) : WorkConversationUiCommand
}

interface WorkConversationUiPort {
    val snapshots: Flow<WorkConversationSceneSnapshot>
    fun snapshot(): WorkConversationSceneSnapshot
    fun submit(command: WorkConversationUiCommand): UiCommandReceipt
}
