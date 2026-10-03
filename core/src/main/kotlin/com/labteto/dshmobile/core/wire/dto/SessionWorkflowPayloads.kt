@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.InternalSerializationApi::class,
)

package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `tool-workflow/run-start` payload — opens one durable top-level workflow record. */
@Serializable
data class ToolWorkflowRunStartData(
    @SerialName("runId") val runId: String,
    @SerialName("name") val name: String,
)

/** `tool-workflow/agent-start` payload — records one published workflow member. */
@Serializable
data class ToolWorkflowAgentStartData(
    @SerialName("runId") val runId: String,
    @SerialName("seq") val seq: Int,
    @SerialName("label") val label: String,
    @SerialName("phase") val phase: String? = null,
    @SerialName("childId") val childId: String,
)

/** `tool-workflow/agent-end` payload — records one member settlement. */
@Serializable
data class ToolWorkflowAgentEndData(
    @SerialName("runId") val runId: String,
    @SerialName("seq") val seq: Int,
    /** 'completed' | 'failed' | 'cancelled'. */
    @SerialName("outcome") val outcome: String,
)

/** `tool-workflow/run-end` payload — closes one workflow record after cleanup. */
@Serializable
data class ToolWorkflowRunEndData(
    @SerialName("runId") val runId: String,
    /** 'completed' | 'cancelled' | 'error'. */
    @SerialName("stopReason") val stopReason: String,
)

/** Tool scoping reapplied on a continuable child resume. */
@Serializable
data class ToolRestriction(
    @SerialName("allow") val allow: List<String>? = null,
    @SerialName("deny") val deny: List<String>? = null,
)

/** `subagent/descriptor` payload — durable child identity and lifecycle mode. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("mode")
sealed class SubagentDescriptorData {
    /** A session-backed subagent that cannot be cold-resumed after its run. */
    @Serializable
    @SerialName("one-shot")
    data class OneShot(
        /** Descriptor format version. */
        @SerialName("version") val version: Int,
        /** The `ctx.subagents` provider name that established the child. */
        @SerialName("provider") val provider: String,
        @SerialName("label") val label: String? = null,
    ) : SubagentDescriptorData()

    /** A session-backed subagent whose declared composition supports cold resume. */
    @Serializable
    @SerialName("continuable")
    data class Continuable(
        @SerialName("version") val version: Int,
        @SerialName("provider") val provider: String,
        @SerialName("label") val label: String,
        @SerialName("agentProvider") val agentProvider: String? = null,
        @SerialName("agentModel") val agentModel: String? = null,
        @SerialName("persona") val persona: String? = null,
        @SerialName("toolFilter") val toolFilter: ToolRestriction? = null,
    ) : SubagentDescriptorData()
}
