package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.tools.LocalToolsRuntime
import javax.inject.Inject
import javax.inject.Singleton

/** Tools page boundary; plugin/runtime implementations stay outside UI. */
@Singleton
class LocalToolsUiFacade @Inject constructor(
    private val tools: LocalToolsRuntime,
) {
    internal suspend fun servers() = tools.servers()
    internal suspend fun githubConfigured() = tools.githubConfigured()
    internal suspend fun configureGitHub(token: String) = tools.configureGitHub(token)
    internal suspend fun clearGitHub() = tools.clearGitHub()
    internal fun installedPluginIds() = tools.installedPluginIds()
    internal suspend fun connectHttp(serverId: String, endpoint: String) = tools.connectHttp(serverId, endpoint)
    internal suspend fun connectStdio(serverId: String, command: List<String>, workingDirectory: String?) =
        tools.connectStdio(serverId, command, workingDirectory)
    internal suspend fun disconnect(serverId: String) = tools.disconnect(serverId)
}
