package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.interop.mcp.McpServerSnapshot

/** ToolsFeature management contract for the one process-wide plugin composition. */
internal interface LocalToolsManagementPort {
    suspend fun servers(): List<McpServerSnapshot>
    /** Emitted by the authoritative MCP registry or Composer selection Owner. */
    fun serverChanges(): kotlinx.coroutines.flow.Flow<Unit>
    suspend fun lifecycleHistory(
        incarnation: String?, afterSequence: Long,
    ): com.labteto.dshmobile.interop.mcp.McpLifecycleHistory
    suspend fun checkMcpHealth(serverId: String): com.labteto.dshmobile.interop.mcp.McpServerHealth
    /** Composer Owner's selected connector IDs; disconnected pins remain selected. */
    fun selectedConnectorIds(): Set<String> = emptySet()

    fun pluginLifecycleSnapshots(): List<com.labteto.dshmobile.harness.plugin.PluginLifecycleSnapshot> =
        emptyList()
    suspend fun recoverFailedPlugin(id: String): List<String> =
        error("当前工具 Owner 不支持插件恢复")
    fun installedPluginIds(): List<String>
    fun capabilityCatalogue(): List<LocalToolCatalogueEntry>
    fun validateCapabilityDraft(
        name: String, expectedSchema: kotlinx.serialization.json.JsonObject,
        argumentsJson: String,
    ): com.labteto.dshmobile.uicontract.UiToolDraftValidation

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

/** Read-only registry metadata; registration never grants invocation or Android permissions. */
internal data class LocalToolCatalogueEntry(val name: String, val family: String,
    val access: String, val approval: String, val requirements: List<String>,
    val parameterSchema: kotlinx.serialization.json.JsonObject)
