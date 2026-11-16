package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.local.LocalToolExecutionCoordinator
import com.labteto.dshmobile.local.agent.LocalAgentRunPolicy
import com.labteto.dshmobile.local.model.LocalModelState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The entire model-facing tool protocol is stable: search and call.
 * Tool definitions live only in ToolRegistry, and are fetched on demand.
 * Permission, plan, subagent and connection policy is checked when a real tool executes.
 */
internal class LocalToolSchemaProjection(
    private val registry: ToolRegistry,
    @Suppress("UNUSED_PARAMETER") executionCoordinator: LocalToolExecutionCoordinator,
) {
    internal data class ToolSchemaSelection(
        val schemas: JsonArray,
        val omittedReasons: Map<String, String> = emptyMap(),
    )

    private fun gatewaySchemas(): JsonArray = JsonArray(
        listOf(DIRECT_TOOL_SEARCH, DIRECT_TOOL_CALL).map { name ->
            requireNotNull(registry.get(name)) { "固定工具入口未注册：$name" }.schema
        },
    )

    /** Team and nested children use the same gateway, not duplicate direct tool definitions. */
    fun teamMemberSchemas(): JsonArray = JsonArray(emptyList())
    fun nestedReadOnlySubagentSchemas(): JsonArray = JsonArray(emptyList())

    fun subagentSchemas(
        allowMutation: Boolean,
        allowVirtualScreen: Boolean,
        enabledOptional: Set<String>,
    ): JsonArray = subagentSelection(allowMutation, allowVirtualScreen, enabledOptional).schemas

    internal fun subagentSelection(
        @Suppress("UNUSED_PARAMETER") allowMutation: Boolean,
        @Suppress("UNUSED_PARAMETER") allowVirtualScreen: Boolean,
        @Suppress("UNUSED_PARAMETER") enabledOptional: Set<String>,
    ): ToolSchemaSelection = ToolSchemaSelection(gatewaySchemas())

    fun modelSchemas(
        policy: LocalAgentRunPolicy,
        modelState: LocalModelState,
        planModeEnabled: Boolean,
        history: List<JsonObject>,
        enabledOptional: Set<String>? = null,
        agentTeamMode: Boolean = false,
    ): JsonArray = modelSelection(
        policy, modelState, planModeEnabled, history, enabledOptional, agentTeamMode,
    ).schemas

    internal fun modelSelection(
        policy: LocalAgentRunPolicy,
        @Suppress("UNUSED_PARAMETER") modelState: LocalModelState,
        @Suppress("UNUSED_PARAMETER") planModeEnabled: Boolean,
        @Suppress("UNUSED_PARAMETER") history: List<JsonObject>,
        @Suppress("UNUSED_PARAMETER") enabledOptional: Set<String>? = null,
        @Suppress("UNUSED_PARAMETER") agentTeamMode: Boolean = false,
    ): ToolSchemaSelection = ToolSchemaSelection(if (policy.toolsEnabled) gatewaySchemas() else JsonArray(emptyList()))

    fun names(schemas: JsonArray): List<String> = schemas.mapNotNull { element ->
        val function = (element as? JsonObject)?.get("function") as? JsonObject
        (function?.get("name") as? JsonPrimitive)?.contentOrNull
    }
}
