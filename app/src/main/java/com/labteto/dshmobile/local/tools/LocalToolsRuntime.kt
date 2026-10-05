package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.interop.mcp.McpServerSnapshot
import com.labteto.dshmobile.local.LocalHarnessEngine
import javax.inject.Inject
import javax.inject.Singleton

/** Narrow MCP/plugin capability used by the Tools screen. */
@Singleton
class LocalToolsRuntime @Inject constructor(
    private val engine: LocalHarnessEngine,
    private val githubCredentials: LocalGitHubCredentialManager,
) {
    internal suspend fun servers(): List<McpServerSnapshot> = engine.mcpServersForUi()
    internal suspend fun githubConfigured(): Boolean = githubCredentials.configured()
    internal suspend fun configureGitHub(token: String) = githubCredentials.configure(token)
    internal suspend fun clearGitHub() = githubCredentials.clear()
    internal fun installedPluginIds(): List<String> = engine.installedPluginIdsForUi()
    internal suspend fun connectHttp(serverId: String, endpoint: String): String = engine.connectMcpHttpForUi(serverId, endpoint)
    internal suspend fun connectStdio(serverId: String, command: List<String>, workingDirectory: String?): String = engine.connectMcpStdioForUi(serverId, command, workingDirectory)
    internal suspend fun disconnect(serverId: String): String =
        engine.disconnectMcpForUi(serverId)
}
