@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.InternalSerializationApi::class,
)

package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Serializable provider or transport failure facts; policy decides whether they are retryable. */
@Serializable
data class LlmFailure(
    /** Human-readable provider or transport failure. */
    @SerialName("message") val message: String,
    /** Stable provider-neutral machine-routing code. */
    @SerialName("code") val code: String,
    /** HTTP status returned by the provider, when available. */
    @SerialName("status") val status: Int? = null,
    /** Provider-requested delay in milliseconds, when valid and available. */
    @SerialName("providerRetryAfterMs") val providerRetryAfterMs: Long? = null,
    /** Opaque provider-issued request identifier for diagnostics. */
    @SerialName("requestId") val requestId: String? = null,
)

/** Token accounting for one model call (cache fields are optional; counts are disjoint). */
@Serializable
data class TokenUsage(
    @SerialName("inputTokens") val inputTokens: Int,
    @SerialName("outputTokens") val outputTokens: Int,
    @SerialName("cacheReadTokens") val cacheReadTokens: Int? = null,
    @SerialName("cacheWriteTokens") val cacheWriteTokens: Int? = null,
    @SerialName("reasoningTokens") val reasoningTokens: Int? = null,
)

/** Why a model response stopped; merge-extensible by `kind`. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("kind")
sealed class FinishReason {
    @Serializable
    @SerialName("stop")
    class Stop : FinishReason()

    @Serializable
    @SerialName("tool-calls")
    class ToolCalls : FinishReason()

    @Serializable
    @SerialName("max-tokens")
    class MaxTokens : FinishReason()

    @Serializable
    @SerialName("aborted")
    data class Aborted(
        @SerialName("failure") val failure: LlmFailure,
    ) : FinishReason()

    @Serializable
    @SerialName("error")
    data class Error(
        @SerialName("failure") val failure: LlmFailure,
    ) : FinishReason()
}

/** Where a message (or injected content) came from; merge-extensible by `kind`. */
@Serializable
data class MessageSource(
    /** 'user' | 'plugin' | 'model' | 'tool' (or a plugin-merged kind). */
    @SerialName("kind") val kind: String,
    /** Present when `kind` is 'plugin'. */
    @SerialName("plugin") val plugin: String? = null,
    /** Present when `kind` is 'model' (the provider route that produced the message). */
    @SerialName("provider") val provider: String? = null,
    /** Present when `kind` is 'model' (the provider model id). */
    @SerialName("model") val model: String? = null,
    /** Adapter-private replay state for model-produced messages. */
    @SerialName("replayState") val replayState: JsonElement? = null,
    /** Present when `kind` is 'tool' (the correlated call id). */
    @SerialName("callId") val callId: String? = null,
    /** Present on the user-rpc provenance the host folds into a prompt's user/message. */
    @SerialName("rpcId") val rpcId: String? = null,
    /** Host-validated browser zone recorded on the exact user message. */
    @SerialName("clientTimeZone") val clientTimeZone: String? = null,
    /** Producer-declared context form ('instructions' | 'catalog' | 'snapshot' | 'notice' | ...). */
    @SerialName("form") val form: String? = null,
    /** Named contributions of a 'snapshot'-form context. */
    @SerialName("sections") val sections: List<ContextSnapshotSection>? = null,
    /** One-line account of a 'notice'-form context. */
    @SerialName("summary") val summary: String? = null,
)

/** One named contribution to a `snapshot`-form context, in assembly order. */
@Serializable
data class ContextSnapshotSection(
    @SerialName("name") val name: String,
    @SerialName("text") val text: String,
)

/** One immutable message representation shared by delivery, durable history, and model requests. */
@Serializable
data class MessageData(
    /** Stable identity preserved across every representation boundary. */
    @SerialName("id") val id: String,
    /** Provider-neutral conversation role ('system' | 'user' | 'assistant'). */
    @SerialName("role") val role: String,
    /** Exact model-facing blocks. */
    @SerialName("content") val content: List<ContentBlock> = emptyList(),
    /** Required source fields supplied by the producer. */
    @SerialName("source") val source: MessageSource,
)
