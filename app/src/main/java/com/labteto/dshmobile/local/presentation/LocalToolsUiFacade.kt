package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.tools.LocalToolsRuntime
import javax.inject.Inject
import javax.inject.Singleton

data class LocalSkillUiEntry(val name: String, val description: String, val modelInvocable: Boolean)
data class LocalPresetSkillUiEntry(val id: String, val title: String, val description: String, val installed: Boolean)
data class LocalSkillEditorUiEntry(val id: String, val document: String)

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
    internal suspend fun installedSkills() = tools.installedSkills().map {
        LocalSkillUiEntry(it.name, it.description, it.modelInvocable)
    }
    internal suspend fun presetSkills() = tools.presetSkills().map {
        LocalPresetSkillUiEntry(it.id, it.title, it.description, it.installed)
    }
    internal suspend fun installPreset(id: String) = tools.installPreset(id)
    internal suspend fun createSkill(id: String, description: String, instructions: String) =
        tools.createSkill(id, description, instructions)
    internal suspend fun removeSkill(id: String) = tools.removeSkill(id)
    internal suspend fun readSkillDocument(id: String) = tools.readSkillDocument(id)
    internal suspend fun updateSkillDocument(id: String, document: String) = tools.updateSkillDocument(id, document)
    internal suspend fun setSkillModelInvocable(id: String, enabled: Boolean) = tools.setSkillModelInvocable(id, enabled)
    internal suspend fun connectHttp(serverId: String, endpoint: String) = tools.connectHttp(serverId, endpoint)
    internal suspend fun connectStdio(serverId: String, command: List<String>, workingDirectory: String?) =
        tools.connectStdio(serverId, command, workingDirectory)
    internal suspend fun disconnect(serverId: String) = tools.disconnect(serverId)
}
