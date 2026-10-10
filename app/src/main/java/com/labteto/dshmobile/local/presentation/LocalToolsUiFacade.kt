package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.tools.LocalToolsRuntime
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityReadinessProjector
import javax.inject.Inject
import javax.inject.Singleton

data class LocalSkillUiEntry(val name: String, val description: String, val modelInvocable: Boolean, val displayName: String = name)
data class LocalPresetSkillUiEntry(val id: String, val title: String, val description: String, val installed: Boolean)
data class LocalSkillEditorUiEntry(val id: String, val document: String)

/** Tools page boundary; plugin/runtime implementations stay outside UI. */
@Singleton
class LocalToolsUiFacade @Inject constructor(
    private val tools: LocalToolsRuntime,
) {
    internal suspend fun servers() = tools.servers()
    internal suspend fun githubConfigured() = tools.githubConfigured()
    /** Work handoff UI projection; capability hints do not grant execution permissions. */
    internal fun workHandoffCapabilityReadiness(
        task: String,
        githubConfigured: Boolean?,
        networkSearchEnabled: Boolean,
        showModelStatus: Boolean = false,
        modelConfigured: Boolean? = null,
        mcpToolsAvailable: Boolean? = null,
        pluginsInstalled: Boolean? = null,
    ) = LocalTaskCapabilityReadinessProjector.project(
        task, githubConfigured, networkSearchEnabled,
        showModelStatus, modelConfigured, mcpToolsAvailable, pluginsInstalled,
    )
    internal suspend fun configureGitHub(token: String) = tools.configureGitHub(token)
    internal suspend fun clearGitHub() = tools.clearGitHub()
    internal fun installedPluginIds() = tools.installedPluginIds()
    /** Registry presence is a configuration hint, not an execution grant. */
    internal suspend fun handoffExternalReadiness(): Pair<Boolean, Boolean> {
        val hasMcpTools = tools.servers().any { it.tools.isNotEmpty() }
        val hasThirdPartyPlugin = tools.installedPluginIds().any { it != "local-builtin" }
        return hasMcpTools to hasThirdPartyPlugin
    }
    internal suspend fun installedSkills() = tools.installedSkills().map {
        LocalSkillUiEntry(it.name, it.description, it.modelInvocable, it.displayName)
    }
    internal suspend fun presetSkills() = tools.presetSkills().map {
        LocalPresetSkillUiEntry(it.id, it.title, it.description, it.installed)
    }
    internal suspend fun installPreset(id: String) = tools.installPreset(id)
    internal suspend fun createSkill(id: String, displayName: String, description: String, instructions: String) =
        tools.createSkill(id, displayName, description, instructions)
    internal suspend fun importSkill(filename: String, bytes: ByteArray) = tools.importSkill(filename, bytes)
    internal suspend fun removeSkill(id: String) = tools.removeSkill(id)
    internal suspend fun readSkillDocument(id: String) = tools.readSkillDocument(id)
    internal suspend fun updateSkillDocument(id: String, document: String) = tools.updateSkillDocument(id, document)
    internal suspend fun setSkillModelInvocable(id: String, enabled: Boolean) = tools.setSkillModelInvocable(id, enabled)
    internal suspend fun connectHttp(serverId: String, endpoint: String) = tools.connectHttp(serverId, endpoint)
    internal suspend fun connectStdio(serverId: String, command: List<String>, workingDirectory: String?) =
        tools.connectStdio(serverId, command, workingDirectory)
    internal suspend fun disconnect(serverId: String) = tools.disconnect(serverId)
}
