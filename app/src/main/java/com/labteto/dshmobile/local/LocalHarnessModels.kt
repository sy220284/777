package com.labteto.dshmobile.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One durable row shown in the on-device Harness transcript. */
@Serializable
data class LocalHarnessMessage(
    val id: String,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val createdAt: Long,
)

@Serializable
enum class LocalConversationMode {
    INDEPENDENT,
    PROJECT,
    CONTINUATION,
}

/** Persisted model history and its user-facing projection. */
@Serializable
data class LocalHarnessSession(
    val id: String = "",
    val title: String = "新会话",
    val updatedAt: Long = 0L,
    val conversationMode: LocalConversationMode = LocalConversationMode.INDEPENDENT,
    val parentSessionId: String? = null,
    val lineageId: String = "",
    val projectId: String? = null,
    val handoffSummary: String? = null,
    val messages: List<LocalHarnessMessage> = emptyList(),
    /**
     * Legacy compatibility only. New snapshots leave this empty; model-visible history is restored
     * from SessionEventLog checkpoints and semantic tail events.
     */
    @SerialName("modelHistory")
    val legacyModelHistory: List<JsonObject> = emptyList(),
    val plan: List<String> = emptyList(),
    val todos: List<LocalTodoItem> = emptyList(),
    val goal: LocalGoal? = null,
    val planMode: Boolean = false,
    /** Highest SessionEvent sequence already reflected in the materialized control-state snapshot. */
    val controlProjectedThroughSequence: Long? = null,
    /** Highest SessionEvent sequence already reflected in the materialized user-facing transcript. */
    val transcriptProjectedThroughSequence: Long? = null,
)

enum class LocalImageInputMode {
    AUTO,
    NATIVE,
    TOOL,
}

data class LocalImportedAttachment(
    val name: String,
    val relativePath: String,
    val mediaType: String,
    val bytes: Long,
    val attachmentId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

data class LocalSessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val blank: Boolean = false,
)

/** One real file currently present in the app-private local Harness workspace. */
data class LocalWorkspaceFile(
    val path: String,
    val bytes: Long,
    val modifiedAt: Long,
)

/** Current-session file projection derived from its durable tool event log. */
data class LocalConversationFiles(
    val artifacts: List<LocalWorkspaceFile> = emptyList(),
    val involved: List<LocalWorkspaceFile> = emptyList(),
) {
    val isEmpty: Boolean get() = artifacts.isEmpty() && involved.isEmpty()
}

/** Lightweight local preview; binary files remain visible without forcing them through UTF-8. */
data class LocalWorkspaceFilePreview(
    val file: LocalWorkspaceFile,
    val text: String? = null,
    val truncated: Boolean = false,
)

/** One persisted implementation task, aligned with the official todo tool. */
@Serializable
data class LocalTodoItem(
    val content: String,
    val status: String,
)

/** The current durable session goal. */
@Serializable
data class LocalGoal(
    val description: String,
    val status: String = "active",
    val note: String? = null,
)

enum class LocalApprovalImpact {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

/** A tool call waiting for the operator because it crossed an approval boundary. */
data class LocalApproval(
    val callId: String,
    val toolName: String,
    val summary: String,
    val arguments: String,
    val access: String,
    val impact: LocalApprovalImpact = LocalApprovalImpact.HIGH,
    val canAutoApproveSafely: Boolean = false,
    val canApproveDeviceTurn: Boolean = false,
)

/** A model question that pauses the current turn until the user answers it. */
data class LocalQuestion(
    val callId: String,
    val question: String,
    val options: List<String> = emptyList(),
)

/** User-visible state for a background command. */
data class LocalJobInfo(
    val id: String,
    val label: String,
    val status: String,
)

/** State rendered by the standalone, on-device Harness screen. */
data class LocalHarnessState(
    val loading: Boolean = true,
    val configured: Boolean = false,
    val model: String = "deepseek-chat",
    val baseUrl: String = "https://api.deepseek.com",
    val mainMaxSteps: Int = 16,
    val subagentMaxSteps: Int = 20,
    val modelAttempts: Int = 3,
    val imageInputMode: LocalImageInputMode = LocalImageInputMode.AUTO,
    val workspacePath: String = "",
    val sessionId: String = "",
    val conversationMode: LocalConversationMode = LocalConversationMode.INDEPENDENT,
    val parentSessionId: String? = null,
    val lineageId: String = "",
    val projectId: String? = null,
    val handoffSummary: String? = null,
    val userRules: String = "",
    val autoRecall: Boolean = true,
    val autoMemory: Boolean = true,
    val sessions: List<LocalSessionSummary> = emptyList(),
    val messages: List<LocalHarnessMessage> = emptyList(),
    val plan: List<String> = emptyList(),
    val todos: List<LocalTodoItem> = emptyList(),
    val goal: LocalGoal? = null,
    val planMode: Boolean = false,
    val safeAutoApprovalEnabled: Boolean = false,
    val deviceApprovalLease: Boolean = false,
    val jobs: List<LocalJobInfo> = emptyList(),
    val queuedInputCount: Int = 0,
    val activeModelRequests: Int = 0,
    val activeAgents: Int = 0,
    val maxModelRequests: Int = 1,
    val maxAgents: Int = 1,
    val resourcePressure: String = "low",
    val running: Boolean = false,
    val pendingApproval: LocalApproval? = null,
    val pendingQuestion: LocalQuestion? = null,
    val error: String? = null,
)

/** One OpenAI-compatible function call emitted by the model. */
data class LocalToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val rawArguments: String,
)

/** Parsed model response retained verbatim for the next request. */
data class LocalModelReply(
    val message: JsonObject,
    val content: String?,
    val reasoning: String?,
    val toolCalls: List<LocalToolCall>,
)
