package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenPromptBreakdown
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/** One OpenAI-compatible function call emitted by the model. */
data class LocalToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val rawArguments: String,
)

data class LocalModelDelta(
    val content: String = "",
    val reasoning: String = "",
)

/** Provider/account identity of the exact route that produced one model reply. */

data class LocalPromptCacheDiagnostic(
    val type: String,
    val reason: String? = null,
    val comparisonReusableTokens: Long? = null,
    val cacheMissedTokens: Long? = null,
)

typealias LocalModelCompletionReason = com.labteto.dshmobile.harness.agent.AgentCompletionReason

/** Parsed model response retained verbatim for the next request. */
data class LocalModelReply(
    val message: JsonObject,
    val content: String?,
    val reasoning: String?,
    val toolCalls: List<LocalToolCall>,
    val usage: DeepSeekTokenUsage = DeepSeekTokenUsage(),
    val requestId: String = "",
    val promptBreakdown: TokenPromptBreakdown = TokenPromptBreakdown(),
    val canonicalMessage: LocalCanonicalMessage? = null,
    val routeIdentity: LocalModelRouteIdentity? = null,
    val promptCacheDiagnostic: LocalPromptCacheDiagnostic? = null,
    /** Native content before display splitting; replay only under the producing route identity. */
    val providerContentForReplay: JsonElement? = null,
    val completionReason: LocalModelCompletionReason = LocalModelCompletionReason.COMPLETED,
    val rawStopReason: String? = null,
)

enum class LocalModelPreviewPhase { IDLE, WAITING, STREAMING, RETRYING }

/** High-frequency model preview kept outside the aggregate runtime state. */
data class LocalHarnessStreamingState(
    val sessionId: String = "",
    val requestId: String = "",
    val usageMode: LocalUsageMode? = null,
    val generation: Long = 0L,
    val assistant: String = "",
    val reasoning: String = "",
    val phase: LocalModelPreviewPhase = LocalModelPreviewPhase.IDLE,
    val attempt: Int = 1,
)

/** Value projection, not an accounting write; usageTracker remains the sole accounting owner. */
internal fun agentReportedModelUsage(usage: DeepSeekTokenUsage): com.labteto.dshmobile.harness.agent.AgentModelUsage? =
    if (!usage.reported) null else com.labteto.dshmobile.harness.agent.AgentModelUsage(
        promptTokens = usage.promptTokens, completionTokens = usage.completionTokens,
        cacheHitTokens = usage.cacheHitTokens, cacheMissTokens = usage.cacheMissTokens,
        cacheWriteTokens = usage.cacheWriteTokens, reasoningTokens = usage.reasoningTokens,
    )
