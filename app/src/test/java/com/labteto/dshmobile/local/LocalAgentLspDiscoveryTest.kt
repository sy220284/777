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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Full Agent discovery route: plugin registry -> task intent -> model schema -> execution. */
class LocalAgentLspDiscoveryTest {
    @get:Rule val workspace = TemporaryFolder()

    @Test fun taskSelectsPreciseLspToolsWithoutDownloadingOrExposingUninstall() = runBlocking {
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
            val coordinator = LocalToolExecutionCoordinator(
                registry = ctx.tools,
                currentSessionId = { "lsp-discovery" },
                planMode = { false },
                requestApproval = { _, _, _ -> true },
            )
            val enabled = linkedSetOf<String>()
            coordinator.prepareWorkTurnCapabilities(
                input = "检查代码的类型错误",
                history = emptyList(),
                gitHubConfigured = { false },
                target = enabled,
            )
            assertTrue("lsp_diagnostics" in enabled)
            assertFalse("lsp_uninstall_component" in enabled)
            assertFalse("lsp_rename_preview" in enabled)
            assertFalse("lsp_definition" in enabled)
            val projection = LocalToolSchemaProjection(ctx.tools, coordinator)
            val toolsInModel = projection.names(projection.modelSchemas(
                policy = localAgentRunPolicy(LocalUsageMode.WORK),
                modelState = LocalModelState(),
                planModeEnabled = false,
                history = emptyList(),
                enabledOptional = enabled,
            ))
            assertTrue("lsp_diagnostics" in toolsInModel)
            assertFalse("lsp_uninstall_component" in toolsInModel)
            assertEquals(0, installs)

            // The Agent can explicitly search for more semantics without a hardcoded allowlist.
            val discovered = coordinator.searchCapabilities("查找引用", enabled)
            assertTrue(discovered.contains("lsp_references"))
            assertTrue("lsp_references" in coordinator.visibleToolNames(
                localAgentRunPolicy(LocalUsageMode.WORK),
            ) || "lsp_references" in projection.names(projection.modelSchemas(
                policy = localAgentRunPolicy(LocalUsageMode.WORK),
                modelState = LocalModelState(),
                planModeEnabled = false,
                history = emptyList(),
                enabledOptional = enabled,
            )))
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
