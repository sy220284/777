package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.interop.lsp.LspPlugin
import com.labteto.dshmobile.local.agent.localAgentRunPolicy
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.tools.LocalToolSchemaProjection
import com.labteto.dshmobile.local.tools.LocalToolRouter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Full Agent discovery route: plugin registry -> task intent -> model schema -> execution. */
class LocalAgentLspDiscoveryTest {
    @get:Rule val workspace = TemporaryFolder()

    @Test fun lspCanBeDirectlyInvokedWithoutAnyOptionalExposure() = runBlocking {
        var installs = 0
        val ctx = HarnessContext()
        val plugin = LspPlugin(
            root = workspace.root,
            json = Json,
            command = { emptyList() },
            canInstallOnDemand = { true },
            installOnDemand = { installs++; emptyList() },
            managedStatus = { "typescript 6.0.1 尚未安装" },
        )
        plugin.install(ctx)
        try {
            for (name in listOf("tool_search", "tool_call")) {
                val schema = com.labteto.dshmobile.local.tools.LocalToolCatalog.specs.first {
                    it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == name
                }.jsonObject
                ctx.tools.register(com.labteto.dshmobile.harness.tools.HarnessTool(
                    name = name, schema = schema,
                    access = com.labteto.dshmobile.harness.tools.ToolAccess.READ_ONLY,
                    approvalPolicy = com.labteto.dshmobile.harness.tools.ToolApprovalPolicy.NEVER,
                    exposure = com.labteto.dshmobile.harness.tools.ToolExposure.CORE,
                    metadata = com.labteto.dshmobile.harness.tools.ToolMetadata("环境"),
                    executor = com.labteto.dshmobile.harness.tools.HarnessToolExecutor { _, _, _ ->
                        com.labteto.dshmobile.harness.tools.ToolResult("stub")
                    },
                ))
            }
            val coordinator = LocalToolExecutionCoordinator(
                registry = ctx.tools,
                currentSessionId = { "lsp-discovery" },
                planMode = { false },
                requestApproval = { _, _, _ -> true },
            )
            val projection = LocalToolSchemaProjection(ctx.tools, coordinator)
            assertEquals(listOf("tool_search", "tool_call"), projection.names(projection.modelSchemas(localAgentRunPolicy(LocalUsageMode.WORK))))
            val args = kotlinx.serialization.json.buildJsonObject {
                put("name", "lsp_status")
                put("arguments", kotlinx.serialization.json.buildJsonObject {})
            }
            val response = coordinator.execute(
                original = LocalToolCall("lsp-direct", "tool_call", args, args.toString()),
                allowMutation = true,
            )
            assertFalse(response.isError)
            assertTrue(response.content.contains("typescript 6.0.1 尚未安装"))
            assertEquals(0, installs)
        } finally {
            plugin.uninstall(ctx)
        }
    }

    @Test fun agentStatusExecutionStaysOfflineAndReportsActualComponentState() = runBlocking {
        var installs = 0
        val ctx = HarnessContext()
        val plugin = LspPlugin(
            root = workspace.root,
            json = Json,
            command = { emptyList() },
            canInstallOnDemand = { true },
            installOnDemand = { installs++; emptyList() },
            managedStatus = { "pyright 1.1.410 尚未安装" },
        )
        plugin.install(ctx)
        try {
            val coordinator = LocalToolExecutionCoordinator(
                registry = ctx.tools,
                currentSessionId = { "status" },
                planMode = { false },
                requestApproval = { _, _, _ -> true },
            )
            val result = coordinator.execute(
                original = LocalToolCall("status-call", "lsp_status", JsonObject(emptyMap()), "{}"),
                allowMutation = true,
            )
            assertFalse(result.isError)
            assertTrue(result.content.contains("pyright 1.1.410 尚未安装"))
            assertEquals(0, installs)
        } finally {
            plugin.uninstall(ctx)
        }
    }
}
