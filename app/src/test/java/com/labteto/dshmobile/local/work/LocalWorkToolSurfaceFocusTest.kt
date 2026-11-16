package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.local.LocalToolExecutionCoordinator
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.localAgentRunPolicy
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.tools.LocalToolCatalog
import com.labteto.dshmobile.local.tools.LocalToolSchemaProjection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The new tool interface is constant across steps, registries and plans. */
class LocalWorkToolSurfaceFocusTest {
    private fun registry() = ToolRegistry().apply {
        for (name in listOf("tool_call", "tool_search")) {
            val schema = LocalToolCatalog.specs.first {
                it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == name
            }.jsonObject
            register(HarnessTool(
                name = name, schema = schema, access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER, exposure = ToolExposure.CORE,
                metadata = ToolMetadata("环境"),
                executor = HarnessToolExecutor { _, _, _ -> ToolResult("gateway") },
            ))
        }
    }

    private fun names(registry: ToolRegistry, plan: Boolean, optional: Set<String>?) =
        LocalToolSchemaProjection(registry, LocalToolExecutionCoordinator(
            registry = registry, currentSessionId = { "work" }, planMode = { plan },
            requestApproval = { _, _, _ -> true },
        )).let { projection ->
            projection.names(projection.modelSchemas(localAgentRunPolicy(LocalUsageMode.WORK)))
        }

    @Test fun modelSurfaceIsStableWhenPluginsAndPreviousSelectionsChange() {
        val registry = registry()
        val expected = listOf("tool_search", "tool_call")
        assertEquals(expected, names(registry, false, emptySet()))
        registry.register(HarnessTool(
            name = "mcp_new", schema = com.labteto.dshmobile.harness.tools.functionToolSchema(
                "mcp_new", "动态接入的工具"),
            access = ToolAccess.READ_ONLY,
            approvalPolicy = ToolApprovalPolicy.NEVER,
            exposure = ToolExposure.OPTIONAL, metadata = ToolMetadata("MCP", discoveryKeywords = setOf("mcp_new")),
            executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
        ))
        assertEquals(expected, names(registry, false, setOf("mcp_new")))
        assertEquals(expected, names(registry, true, setOf("mcp_new")))
        assertTrue(registry.get("mcp_new") != null)
    }

    @Test fun toolDefinitionsAreAbsentFromDirectModelSurface() {
        val registry = registry()
        val actual = names(registry, false, setOf("read", "github_search"))
        assertEquals(2, actual.size)
        assertFalse("read" in actual)
        assertFalse("github_search" in actual)
    }

    @Test fun ordinaryChatDoesNotEnableExecutableTools() {
        val registry = registry()
        val projection = LocalToolSchemaProjection(registry, LocalToolExecutionCoordinator(
            registry = registry, currentSessionId = { "chat" }, planMode = { false },
            requestApproval = { _, _, _ -> true },
        ))
        assertTrue(projection.modelSchemas(localAgentRunPolicy(LocalUsageMode.CHAT)).isEmpty())
    }
}
