package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Pure model-visible projection of the registered tool catalog. */
internal class LocalToolSchemaProjection(
    private val registry: ToolRegistry,
    private val executionCoordinator: LocalToolExecutionCoordinator,
) {
    fun subagentSchemas(
        allowMutation: Boolean,
        allowVirtualScreen: Boolean,
        enabledOptional: Set<String>,
    ): JsonArray {
        val enabled = enabledOptional +
            if (allowVirtualScreen) SUBAGENT_VIRTUAL_SCREEN_TOOLS else emptySet()
        val tools = registry.names()
            .mapNotNull(registry::get)
            .filter { tool -> tool.name !in SUBAGENT_EXCLUDED_TOOLS }
            .filter { tool -> tool.name !in SUBAGENT_VIRTUAL_SCREEN_TOOLS || allowVirtualScreen }
            .filter { tool -> allowMutation || tool.name != "download_file" }
            .filter { tool ->
                allowMutation ||
                    tool.access in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK) ||
                    (allowVirtualScreen && tool.name in SUBAGENT_VIRTUAL_SCREEN_TOOLS)
            }
        return LocalToolRouter.visibleSchemas(tools, enabled)
    }

    fun modelSchemas(
        policy: LocalAgentRunPolicy,
        state: LocalHarnessState,
        history: List<JsonObject>,
        enabledOptional: Set<String>? = null,
    ): JsonArray {
        if (!policy.toolsEnabled) return JsonArray(emptyList())
        val promptBudget = optionalToolPromptBudgetForRoute(state, history)
        val enabled = enabledOptional ?: executionCoordinator.enabledOptionalSnapshot()
        val tools = registry.names()
            .mapNotNull(registry::get)
            .filter { tool ->
                !state.planMode || LocalToolPolicy.allowedInPlan(tool.name, tool.access)
            }
        return LocalToolRouter.visibleSchemas(
            tools = tools,
            enabledOptional = enabled,
            maxOptionalDefinitionTokens = promptBudget,
        )
    }

    fun names(schemas: JsonArray): List<String> = schemas.mapNotNull { element ->
        val function = (element as? JsonObject)?.get("function") as? JsonObject
        (function?.get("name") as? JsonPrimitive)?.contentOrNull
    }


}
