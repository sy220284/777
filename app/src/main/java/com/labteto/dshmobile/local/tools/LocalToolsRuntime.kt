package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.interop.mcp.McpServerSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/** Narrow MCP/plugin capability used by the Tools screen. */
@Singleton
class LocalToolsRuntime @Inject internal constructor(
    private val management: LocalToolsManagementPort,
    private val githubCredentials: LocalGitHubCredentialManager,
) {
    internal suspend fun servers(): List<McpServerSnapshot> = management.servers()
    internal suspend fun githubConfigured(): Boolean = githubCredentials.configured()
    internal suspend fun configureGitHub(token: String) = githubCredentials.configure(token)
    internal suspend fun clearGitHub() = githubCredentials.clear()
    internal fun installedPluginIds(): List<String> = management.installedPluginIds()
    internal suspend fun connectHttp(serverId: String, endpoint: String): String =
        management.connectHttp(serverId, endpoint)
    internal suspend fun connectStdio(
        serverId: String,
        command: List<String>,
        workingDirectory: String?,
    ): String = management.connectStdio(serverId, command, workingDirectory)
    internal suspend fun disconnect(serverId: String): String =
        management.disconnect(serverId)
}
