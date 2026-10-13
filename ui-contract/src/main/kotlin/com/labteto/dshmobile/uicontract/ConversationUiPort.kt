package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.Flow

const val UI_CONTRACT_VERSION = 1

/** Opaque owner-issued activation identity; UI cannot derive execution authority from this value. */
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
    QUEUE_FULL, QUEUE_UNAVAILABLE, APPROVAL_EXPIRED, UNSUPPORTED,
}

sealed interface UiCommandReceipt {
    /** Accepted is admission, not execution completion or verification. */
    data object Accepted : UiCommandReceipt
    data object Queued : UiCommandReceipt
    data class Rejected(val reason: UiCommandRejection, val limit: Int? = null) : UiCommandReceipt
    data class Failed(val message: String) : UiCommandReceipt
    data object OutcomeUnknown : UiCommandReceipt
}

enum class UiMessageRole { USER, ASSISTANT, SYSTEM, TOOL }
data class UiMessageSnapshot(val id: String, val role: UiMessageRole, val text: String, val createdAt: Long)

data class ChatSceneSnapshot(
    val identity: UiSessionIdentity?,
    val status: UiSceneStatus,
    val phase: UiExecutionPhase,
    val messages: List<UiMessageSnapshot>,
    val queuedInputCount: Int,
    val send: UiActionAvailability,
    val error: String?,
)

sealed interface ChatUiCommand {
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
)

data class WorkConversationSceneSnapshot(
    val identity: UiSessionIdentity?,
    val status: UiSceneStatus,
    val phase: UiExecutionPhase,
    val messages: List<UiMessageSnapshot>,
    val queuedInputCount: Int,
    val send: UiActionAvailability,
    val approval: UiApprovalSnapshot?,
    val error: String?,
)

sealed interface WorkConversationUiCommand {
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
