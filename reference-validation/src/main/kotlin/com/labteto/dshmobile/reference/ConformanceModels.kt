package com.labteto.dshmobile.reference

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolCallFixture(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

@Serializable
data class ModelReplyFixture(
    val content: String = "",
    val toolCalls: List<ToolCallFixture> = emptyList(),
)

@Serializable
data class ConformanceVector(
    val id: String,
    val input: String,
    val modelReplies: List<ModelReplyFixture>,
    val toolOutputs: Map<String, String> = emptyMap(),
)

@Serializable
data class CanonicalEvent(
    val type: String,
    val step: Int? = null,
    val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val content: String? = null,
    val reason: String? = null,
)
