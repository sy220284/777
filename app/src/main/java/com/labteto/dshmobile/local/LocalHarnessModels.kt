package com.labteto.dshmobile.local

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

/** Persisted model history and its user-facing projection. */
@Serializable
data class LocalHarnessSession(
    val messages: List<LocalHarnessMessage> = emptyList(),
    val modelHistory: List<JsonObject> = emptyList(),
)

/** A destructive or command-execution tool call waiting for the operator. */
data class LocalApproval(
    val callId: String,
    val toolName: String,
    val summary: String,
    val arguments: String,
)

/** State rendered by the standalone, on-device Harness screen. */
data class LocalHarnessState(
    val loading: Boolean = true,
    val configured: Boolean = false,
    val model: String = "deepseek-chat",
    val baseUrl: String = "https://api.deepseek.com",
    val workspacePath: String = "",
    val messages: List<LocalHarnessMessage> = emptyList(),
    val plan: List<String> = emptyList(),
    val running: Boolean = false,
    val pendingApproval: LocalApproval? = null,
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
