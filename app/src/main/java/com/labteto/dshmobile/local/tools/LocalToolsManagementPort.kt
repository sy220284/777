package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.interop.mcp.McpServerSnapshot

/** ToolsFeature management contract for the one process-wide plugin composition. */
internal interface LocalToolsManagementPort {
    suspend fun servers(): List<McpServerSnapshot>

    fun installedPluginIds(): List<String>

    fun installedSkills(): List<LocalInstalledSkill>
    fun presetSkills(): List<LocalPresetSkill>
    fun installPreset(id: String)
    fun createSkill(id: String, displayName: String, description: String, instructions: String)
    fun importSkill(filename: String, bytes: ByteArray): String
    fun removeSkill(id: String)
    fun readSkillDocument(id: String): String
    fun updateSkillDocument(id: String, document: String)
    fun setSkillModelInvocable(id: String, enabled: Boolean)

    suspend fun connectHttp(serverId: String, endpoint: String): String

    suspend fun connectStdio(
        serverId: String,
        command: List<String>,
        workingDirectory: String?,
    ): String

    suspend fun disconnect(serverId: String): String
}
