package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenPromptBreakdown
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class LocalImageInputMode {
    AUTO,
    NATIVE,
    TOOL,
}

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
@Serializable
data class LocalModelRouteIdentity(
    val profileId: String? = null,
    val provider: String = "",
    val model: String = "",
    val baseUrl: String = "",
    val authKind: String = "",
    val protocol: String = "",
    val fingerprint: String = "",
)

data class LocalPromptCacheDiagnostic(
    val type: String,
    val reason: String? = null,
    val comparisonReusableTokens: Long? = null,
    val cacheMissedTokens: Long? = null,
)

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
)

/** High-frequency model preview kept outside the aggregate runtime state. */
data class LocalHarnessStreamingState(
    val sessionId: String = "",
    val requestId: String = "",
    val usageMode: LocalUsageMode? = null,
    val generation: Long = 0L,
    val assistant: String = "",
    val reasoning: String = "",
)
