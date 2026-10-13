package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.local.LocalToolExecutionCoordinator
import com.labteto.dshmobile.local.agent.LocalAgentRunPolicy
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.runtime.SUBAGENT_EXCLUDED_TOOLS
import com.labteto.dshmobile.local.runtime.SUBAGENT_VIRTUAL_SCREEN_TOOLS
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
    ): JsonArray = subagentSelection(allowMutation, allowVirtualScreen, enabledOptional).schemas

    /** The same admission decision feeds subagent visibility and diagnostics. */
    internal fun subagentSelection(
        allowMutation: Boolean,
        allowVirtualScreen: Boolean,
        enabledOptional: Set<String>,
    ): LocalToolRouter.ToolSchemaSelection {
        val enabled = enabledOptional +
            if (allowVirtualScreen) SUBAGENT_VIRTUAL_SCREEN_TOOLS else emptySet()
        val tools = registry.names()
            .mapNotNull(registry::get)
            .filter { tool -> executionCoordinator.isNetworkSearchPermitted(tool.name) }
            .filter { tool -> tool.name !in SUBAGENT_EXCLUDED_TOOLS }
            .filter { tool -> tool.name !in SUBAGENT_VIRTUAL_SCREEN_TOOLS || allowVirtualScreen }
            .filter { tool -> allowMutation || tool.name != "download_file" }
            .filter { tool ->
                allowMutation ||
                    tool.access in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK) ||
                    (allowVirtualScreen && tool.name in SUBAGENT_VIRTUAL_SCREEN_TOOLS)
            }
        val selection = LocalToolRouter.selectSchemas(
            if (allowMutation) tools else tools.map(HarnessTool::forReadOnlyModelScope),
            enabled,
        )
        val unavailable = enabled.mapNotNull { name ->
            val registered = registry.get(name)
            val reason = when {
                registered == null -> "已选工具未注册或已卸载；请重新发现能力"
                !executionCoordinator.isNetworkSearchPermitted(name) -> "网络搜索设置已关闭"
                name in SUBAGENT_EXCLUDED_TOOLS -> "此工具仅允许父智能体使用"
                name in SUBAGENT_VIRTUAL_SCREEN_TOOLS && !allowVirtualScreen ->
                    "本次子任务未授权虚拟屏幕"
                !allowMutation && (name == "download_file" ||
                    registered.access !in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK) &&
                    !(allowVirtualScreen && name in SUBAGENT_VIRTUAL_SCREEN_TOOLS)) ->
                    "子智能体当前只读，不允许此工具"
                else -> null
            }
            reason?.let { name to it }
        }.toMap()
        return selection.copy(omittedReasons = unavailable + selection.omittedReasons)
    }

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

    /** Single source for the model-facing tool surface and omitted capability reasons. */
    internal fun modelSelection(
        policy: LocalAgentRunPolicy,
        modelState: LocalModelState,
        planModeEnabled: Boolean,
        history: List<JsonObject>,
        enabledOptional: Set<String>? = null,
        agentTeamMode: Boolean = false,
    ): LocalToolRouter.ToolSchemaSelection {
        if (!policy.toolsEnabled) return LocalToolRouter.ToolSchemaSelection(
            schemas = JsonArray(emptyList()),
            omittedReasons = emptyMap(),
            compactedNames = emptySet(),
        )
        val promptBudget = if (agentTeamMode) LocalToolRouter.DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS
            else optionalToolPromptBudgetForRoute(modelState, history)
        val enabled = enabledOptional ?: executionCoordinator.enabledOptionalSnapshot()
        val tools = registry.names()
            .mapNotNull(registry::get)
            .filter { tool -> executionCoordinator.isNetworkSearchPermitted(tool.name) }
            .filter { tool ->
                !planModeEnabled || LocalToolPolicy.allowedInPlan(tool.name, tool.access)
            }
        val selection = LocalToolRouter.selectSchemas(
            tools = if (planModeEnabled) tools.map(HarnessTool::forReadOnlyModelScope) else tools,
            enabledOptional = enabled,
            maxOptionalDefinitionTokens = promptBudget,
        )
        val unavailable = enabled.mapNotNull { name ->
            val registered = registry.get(name)
            val reason = when {
                registered == null -> "已选工具未注册或已卸载；请重新发现能力"
                !executionCoordinator.isNetworkSearchPermitted(name) -> "网络搜索设置已关闭"
                planModeEnabled && !LocalToolPolicy.allowedInPlan(name, registered.access) ->
                    "规划模式不允许此工具；退出规划模式后可重新选择"
                else -> null
            }
            reason?.let { name to it }
        }.toMap()
        return selection.copy(omittedReasons = unavailable + selection.omittedReasons)
    }

    fun names(schemas: JsonArray): List<String> = schemas.mapNotNull { element ->
        val function = (element as? JsonObject)?.get("function") as? JsonObject
        (function?.get("name") as? JsonPrimitive)?.contentOrNull
    }


}
