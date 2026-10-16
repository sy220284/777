package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.tools.LocalToolsRuntime
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityReadinessProjector
import javax.inject.Inject
import javax.inject.Singleton

/** Tools page boundary; plugin/runtime implementations stay outside UI. */
@Singleton
class LocalToolsUiFacade @Inject internal constructor(
    private val tools: LocalToolsRuntime,
    private val gitHubSkillImporter: com.labteto.dshmobile.local.tools.LocalGitHubSkillImporter,
) : com.labteto.dshmobile.uicontract.ToolsUiPort {
    override suspend fun servers() = tools.servers().map {
        val lifecycle = it.lifecycle
        val registration = when (lifecycle) {
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.REGISTERED ->
                com.labteto.dshmobile.uicontract.UiToolRegistration.REGISTERED
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.DISCONNECTING ->
                com.labteto.dshmobile.uicontract.UiToolRegistration.REMOVING
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.CONNECTING,
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.RECONNECTING ->
                com.labteto.dshmobile.uicontract.UiToolRegistration.PENDING
        }
        val connection = when (lifecycle) {
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.REGISTERED ->
                com.labteto.dshmobile.uicontract.UiToolConnection.HEALTH_UNCHECKED
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.CONNECTING ->
                com.labteto.dshmobile.uicontract.UiToolConnection.CONNECTING
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.RECONNECTING ->
                com.labteto.dshmobile.uicontract.UiToolConnection.RECONNECTING
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.DISCONNECTING ->
                com.labteto.dshmobile.uicontract.UiToolConnection.DISCONNECTING
        }
        com.labteto.dshmobile.uicontract.UiMcpServerSnapshot(it.id, it.transport, it.target, it.tools,
            registration = registration, connection = connection)
    }
    override suspend fun checkServerHealth(serverId: String): com.labteto.dshmobile.uicontract.UiMcpHealth =
        when (tools.checkMcpHealth(serverId)) {
            com.labteto.dshmobile.interop.mcp.McpServerHealth.RESPONDED ->
                com.labteto.dshmobile.uicontract.UiMcpHealth.RESPONDED
            com.labteto.dshmobile.interop.mcp.McpServerHealth.BUSY ->
                com.labteto.dshmobile.uicontract.UiMcpHealth.BUSY
            com.labteto.dshmobile.interop.mcp.McpServerHealth.NOT_CONNECTED ->
                com.labteto.dshmobile.uicontract.UiMcpHealth.NOT_CONNECTED
            com.labteto.dshmobile.interop.mcp.McpServerHealth.UNCONFIRMED ->
                com.labteto.dshmobile.uicontract.UiMcpHealth.UNCONFIRMED
        }
    override suspend fun githubConfigured() = tools.githubConfigured()
    /** Work handoff UI projection; capability hints do not grant execution permissions. */
    internal fun workHandoffCapabilityReadiness(
        task: String,
        githubConfigured: Boolean?,
        networkSearchEnabled: Boolean,
        showModelStatus: Boolean = false,
        modelConfigured: Boolean? = null,
        mcpToolsAvailable: Boolean? = null,
        pluginsInstalled: Boolean? = null,
        accessibilityActive: Boolean? = null,
        notificationAccessActive: Boolean? = null,
    ) = LocalTaskCapabilityReadinessProjector.project(
        task, githubConfigured, networkSearchEnabled,
        showModelStatus, modelConfigured, mcpToolsAvailable, pluginsInstalled,
        accessibilityActive, notificationAccessActive,
    )
    override suspend fun configureGitHub(token: String) { tools.configureGitHub(token) }
    override suspend fun clearGitHub() = tools.clearGitHub()
    override fun capabilityCatalogue() = tools.capabilityCatalogue().map {
        com.labteto.dshmobile.uicontract.UiToolCapabilitySnapshot(it.name, it.family, it.access, it.approval, it.requirements)
    }
    override fun installedPluginIds() = tools.installedPluginIds()
    /** Registry presence is a configuration hint, not an execution grant. */
    internal suspend fun handoffExternalReadiness(): Pair<Boolean, Boolean> {
        val hasMcpTools = tools.servers().any { it.tools.isNotEmpty() }
        val hasThirdPartyPlugin = tools.installedPluginIds().any { it != "android-local-builtins" }
        return hasMcpTools to hasThirdPartyPlugin
    }
    override suspend fun installedSkills() = tools.installedSkills().map {
        com.labteto.dshmobile.uicontract.UiSkillSnapshot(it.name, it.description, it.modelInvocable, it.displayName)
    }
    override suspend fun presetSkills() = tools.presetSkills().map {
        com.labteto.dshmobile.uicontract.UiPresetSkillSnapshot(it.id, it.title, it.description, it.installed)
    }
    override suspend fun curatedGitHubSkills() = gitHubSkillImporter.curatedSkills()
    override suspend fun installGitHubSkill(link: String) = gitHubSkillImporter.install(link)
    override suspend fun installPreset(id: String) = tools.installPreset(id)
    override suspend fun createSkill(id: String, displayName: String, description: String, instructions: String) =
        tools.createSkill(id, displayName, description, instructions)
    override suspend fun importSkill(filename: String, bytes: ByteArray) = tools.importSkill(filename, bytes)
    override suspend fun removeSkill(id: String) = tools.removeSkill(id)
    override suspend fun readSkillDocument(id: String) = tools.readSkillDocument(id)
    override suspend fun updateSkillDocument(id: String, document: String) = tools.updateSkillDocument(id, document)
    override suspend fun setSkillModelInvocable(id: String, enabled: Boolean) = tools.setSkillModelInvocable(id, enabled)
    override suspend fun connectHttp(serverId: String, endpoint: String) = tools.connectHttp(serverId, endpoint)
    override suspend fun connectStdio(serverId: String, command: List<String>, workingDirectory: String?) =
        tools.connectStdio(serverId, command, workingDirectory)
    override suspend fun disconnect(serverId: String) = tools.disconnect(serverId)
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalToolsUiBindings {
    @dagger.Binds abstract fun bindToolsUiPort(facade: LocalToolsUiFacade): com.labteto.dshmobile.uicontract.ToolsUiPort
}
