package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.automation.WebhookController
import com.labteto.dshmobile.local.tools.LocalToolsRuntime
import javax.inject.Inject
import javax.inject.Singleton

data class LocalWebhookUiState(
    val enabled: Boolean = false,
    val port: Int = 8765,
    val tokenHint: String? = null,
)

data class LocalSkillUiEntry(val name: String, val description: String, val modelInvocable: Boolean)

/** Tools page boundary; plugin/runtime implementations stay outside UI. */
@Singleton
class LocalToolsUiFacade @Inject constructor(
    private val tools: LocalToolsRuntime,
    private val webhook: WebhookController,
) {
    internal suspend fun servers() = tools.servers()
    internal suspend fun githubConfigured() = tools.githubConfigured()
    internal suspend fun configureGitHub(token: String) = tools.configureGitHub(token)
    internal suspend fun clearGitHub() = tools.clearGitHub()
    internal fun installedPluginIds() = tools.installedPluginIds()
    internal suspend fun installedSkills() = tools.installedSkills().map {
        LocalSkillUiEntry(it.name, it.description, it.modelInvocable)
    }
    internal suspend fun connectHttp(serverId: String, endpoint: String) = tools.connectHttp(serverId, endpoint)
    internal suspend fun connectStdio(serverId: String, command: List<String>, workingDirectory: String?) =
        tools.connectStdio(serverId, command, workingDirectory)
    internal suspend fun disconnect(serverId: String) = tools.disconnect(serverId)

    internal suspend fun webhookStatus(): LocalWebhookUiState = webhook.status().let {
        LocalWebhookUiState(
            enabled = it.enabled,
            port = it.port,
            tokenHint = it.tokenHint,
        )
    }

    internal suspend fun startWebhook(port: Int): String = webhook.start(port = port, allowLan = false)
    internal fun stopWebhook(): Boolean = webhook.stop()
    internal suspend fun copyWebhookToken(): String = webhook.copyTokenToClipboard()
    internal suspend fun rotateWebhookToken(): String = webhook.rotateToken()
}
