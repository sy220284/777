@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.InternalSerializationApi::class,
)

package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The conversation's call configuration (provider, model, reasoning effort, sampling scalars). */
@Serializable
data class LlmCallConfig(
    @SerialName("provider") val provider: String,
    @SerialName("model") val model: String,
    @SerialName("reasoningEffort") val reasoningEffort: String? = null,
    @SerialName("temperature") val temperature: Double? = null,
    @SerialName("maxTokens") val maxTokens: Int? = null,
    @SerialName("stop") val stop: List<String>? = null,
)

/** Effective config fields supplied by exact-model adapter resolution rather than the caller. */
@Serializable
data class LlmCallConfigAdapterDefaults(
    @SerialName("reasoningEffort") val reasoningEffort: Boolean? = null,
    @SerialName("maxTokens") val maxTokens: Boolean? = null,
)

/** JSON-schema description of a tool, as sent to the model. */
@Serializable
data class ToolSchema(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    /** JSON Schema object for the arguments. */
    @SerialName("parameters") val parameters: JsonElement,
)

/** Logged request state outside derived history (the `request/header` payload). */
@Serializable
data class EpochHeader(
    @SerialName("config") val config: LlmCallConfig,
    @SerialName("adapterDefaults") val adapterDefaults: LlmCallConfigAdapterDefaults? = null,
    /** Rendered system prompt text; absent for a system-less request. */
    @SerialName("system") val system: String? = null,
    /** Assembled tool schemas; absent for a tool-less request. */
    @SerialName("tools") val tools: List<ToolSchema>? = null,
)

/** Why a `request/header` snapshot was appended ('initial' | 'resume' | 'change'). */
@Serializable
enum class RequestHeaderReason {
    @SerialName("initial")
    INITIAL,

    @SerialName("resume")
    RESUME,

    @SerialName("change")
    CHANGE,
}
