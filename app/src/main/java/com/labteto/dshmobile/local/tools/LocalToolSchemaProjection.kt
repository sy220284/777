package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.local.LocalToolExecutionCoordinator
import com.labteto.dshmobile.local.agent.LocalAgentRunPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Fixed model gateway; the live registry and execution coordinator own real tools. */
internal class LocalToolSchemaProjection(
    private val registry: ToolRegistry,
    @Suppress("UNUSED_PARAMETER") executionCoordinator: LocalToolExecutionCoordinator,
) {
    private fun gatewaySchemas(): JsonArray = JsonArray(
        listOf(DIRECT_TOOL_SEARCH, DIRECT_TOOL_CALL).map { name ->
            requireNotNull(registry.get(name)) { "固定工具入口未注册：$name" }.schema
        },
    )

    /** Team and nested child commands are resolved by tool_call at execution time. */
    fun teamMemberSchemas(): JsonArray = JsonArray(emptyList())
    fun nestedReadOnlySubagentSchemas(): JsonArray = JsonArray(emptyList())

    fun subagentSchemas(): JsonArray = gatewaySchemas()

    fun modelSchemas(policy: LocalAgentRunPolicy): JsonArray =
        if (policy.toolsEnabled) gatewaySchemas() else JsonArray(emptyList())

    fun names(schemas: JsonArray): List<String> = schemas.mapNotNull { element ->
        val function = (element as? JsonObject)?.get("function") as? JsonObject
        (function?.get("name") as? JsonPrimitive)?.contentOrNull
    }
}
