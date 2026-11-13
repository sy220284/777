@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.InternalSerializationApi::class,
)

package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** `turn/start` payload. */
@Serializable
data class TurnStartData(
    @SerialName("turn") val turn: Int,
)

/** Why a turn ended; merge-extensible by `kind`. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("kind")
sealed class TurnEndReason {
    @Serializable
    @SerialName("completed")
    class Completed : TurnEndReason()

    @Serializable
    @SerialName("aborted")
    data class Aborted(
        @SerialName("reason") val reason: AgentCancelCause,
    ) : TurnEndReason()

    @Serializable
    @SerialName("blocked")
    class Blocked : TurnEndReason()

    @Serializable
    @SerialName("error")
    data class Error(
        @SerialName("error") val error: LlmFailure,
    ) : TurnEndReason()

    @Serializable
    @SerialName("max-tokens")
    class MaxTokens : TurnEndReason()

    @Serializable
    @SerialName("interrupted")
    class Interrupted : TurnEndReason()
}

/** Why an active agent driver was cancelled (or the durable 'legacy' record). */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("kind")
sealed class AgentCancelCause {
    @Serializable
    @SerialName("user")
    class User : AgentCancelCause()

    @Serializable
    @SerialName("parent")
    class Parent : AgentCancelCause()

    @Serializable
    @SerialName("hook")
    data class Hook(
        @SerialName("reason") val reason: String,
    ) : AgentCancelCause()

    @Serializable
    @SerialName("disposed")
    class Disposed : AgentCancelCause()

    /** Durable record whose coarse original carried no cause. */
    @Serializable
    @SerialName("legacy")
    class Legacy : AgentCancelCause()
}

/** `turn/end` payload. */
@Serializable
data class TurnEndData(
    @SerialName("turn") val turn: Int,
    @SerialName("reason") val reason: TurnEndReason,
)

/** `step/start` payload. */
@Serializable
data class StepStartData(
    @SerialName("turn") val turn: Int,
    @SerialName("step") val step: Int,
)

/** `step/end` payload. */
@Serializable
data class StepEndData(
    @SerialName("turn") val turn: Int,
    @SerialName("step") val step: Int,
)

/**
 * `assistant/attempt` payload — one model attempt that committed no surface message (harness
 * 0.1.3, session format v2). A failed, retried or cancelled attempt reaches settlement here
 * rather than fabricating model-visible history; [stream] is its exact compact raw stream.
 */
@Serializable
data class AssistantAttemptData(
    @SerialName("turn") val turn: Int,
    @SerialName("step") val step: Int,
    /** Compact `AssistantStreamRecord[]`; historical live assistant stream format. */
    @SerialName("stream") val stream: JsonElement = kotlinx.serialization.json.JsonArray(emptyList()),
)

/** `assistant/message` payload — assembled assistant message plus optional token accounting. */
@Serializable
data class AssistantMessageData(
    @SerialName("turn") val turn: Int,
    @SerialName("step") val step: Int,
    @SerialName("message") val message: MessageData,
    /**
     * Exact timed model stream, compacted without joining delta boundaries (harness 0.1.3).
     * Format v2 embeds it here instead of logging one `assistant/chunk` per token; the assembled
     * [message] is what a transcript reads, and the stream is replay data.
     */
    @SerialName("stream") val stream: JsonElement? = null,
    /** Present when the adapter reported token accounting. */
    @SerialName("usage") val usage: TokenUsage? = null,
    /**
     * True when this message is the prefix a cancelled turn had already delivered, finalized so
     * the partial answer survives (harness 0.1.0-rc.8; undispatched tool calls are absent). An
     * rc.7 host sends no such key and appends no message at all, which is why the fold keeps a
     * fallback that infers interruption from the turn's own ending.
     */
    @SerialName("interrupted") val interrupted: Boolean? = null,
)

/** `tool/call` payload — the model requested one tool invocation. */
@Serializable
data class ToolCallData(
    @SerialName("turn") val turn: Int,
    @SerialName("step") val step: Int,
    @SerialName("callId") val callId: String,
    @SerialName("name") val name: String,
    /** Raw JSON string exactly as the model produced it (unparsed). */
    @SerialName("arguments") val arguments: String,
)

/** Internal failure identity of a completed tool call. */
@Serializable
data class ToolErrorRef(
    @SerialName("name") val name: String,
    @SerialName("code") val code: String,
)

/** `tool/result` payload. */
@Serializable
data class ToolResultData(
    @SerialName("turn") val turn: Int,
    @SerialName("step") val step: Int,
    @SerialName("message") val message: MessageData,
    @SerialName("error") val error: ToolErrorRef? = null,
    /** Tool-private presentation payload; opaque to the core. */
    @SerialName("meta") val meta: JsonElement? = null,
)
