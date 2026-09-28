package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.interop.mcp.McpServerSnapshot
import com.labteto.dshmobile.local.LocalHarnessEngine
import javax.inject.Inject
import javax.inject.Singleton

/** Narrow MCP/plugin capability used by the Tools screen. */
@Singleton
class LocalToolsRuntime @Inject constructor(
    private val engine: LocalHarnessEngine,
) {
    internal suspend fun servers(): List<McpServerSnapshot> = engine.mcpServersForUi()
    internal suspend fun githubConfigured(): Boolean = engine.githubConnectorConfiguredForUi()
    internal suspend fun configureGitHub(token: String) = engine.configureGitHubConnectorForUi(token)
    internal suspend fun clearGitHub() = engine.clearGitHubConnectorForUi()
    internal fun installedPluginIds(): List<String> = engine.installedPluginIdsForUi()
    internal suspend fun connectHttp(serverId: String, endpoint: String): String =
        engine.connectMcpHttpForUi(serverId, endpoint)
    internal suspend fun disconnect(serverId: String): String =
        engine.disconnectMcpForUi(serverId)
}
