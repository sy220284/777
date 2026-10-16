package com.labteto.dshmobile.uicontract

enum class UiToolRegistration { REGISTERED, PENDING, REMOVING }
/** Explicit ping only; RESPONDED never implies tool authorization or continuous availability. */
enum class UiMcpHealth { RESPONDED, BUSY, NOT_CONNECTED, UNCONFIRMED, UNSUPPORTED }
enum class UiToolSelection { UNKNOWN, SELECTED, NOT_SELECTED }
enum class UiToolConnection { HEALTH_UNCHECKED, CONNECTING, RECONNECTING, DISCONNECTING }
enum class UiToolAuthorization { REQUIRED_PER_CALL }

/** Selection is only the persisted composer preference, never execution authorization. */
fun uiMcpToolSelection(serverId: String, selectedConnectorIds: Set<String>): UiToolSelection =
    if (serverId in selectedConnectorIds) UiToolSelection.SELECTED else UiToolSelection.NOT_SELECTED
data class UiMcpServerSnapshot(
    val id: String, val transport: String, val target: String, val tools: List<String>,
    val registration: UiToolRegistration = UiToolRegistration.REGISTERED,
    val selection: UiToolSelection = UiToolSelection.UNKNOWN,
    val connection: UiToolConnection = UiToolConnection.HEALTH_UNCHECKED,
    val authorization: UiToolAuthorization = UiToolAuthorization.REQUIRED_PER_CALL,
)
enum class UiPluginLifecycleState { INSTALLING, ACTIVE, REPLACING, UNINSTALLING, FAILED }
data class UiPluginLifecycle(val id: String, val state: UiPluginLifecycleState)

data class UiToolCapabilitySnapshot(val name: String, val family: String, val access: String,
    val approval: String, val requirements: List<String>,
    /** Exact registry-owned parameter schema for a read-only input form; not an invocation grant. */
    val parameterSchema: kotlinx.serialization.json.JsonObject? = null)

sealed interface UiToolDraftValidation {
    data object Valid : UiToolDraftValidation
    data class Rejected(val reason: String) : UiToolDraftValidation
}
data class UiSkillSnapshot(val name: String, val description: String, val modelInvocable: Boolean,
    val displayName: String = name)
data class UiPresetSkillSnapshot(val id: String, val title: String, val description: String, val installed: Boolean)
data class UiSkillEditorSnapshot(val id: String, val document: String)

/** Metadata is not an invocation grant. Actual tool execution remains outside this management port. */
interface ToolsUiPort {
    suspend fun servers(): List<UiMcpServerSnapshot>
    /** Owner-issued lifecycle/selection changes; not a remote health or tool authorization grant. */
    fun serverChanges(): kotlinx.coroutines.flow.Flow<Unit>
    suspend fun checkServerHealth(serverId: String): UiMcpHealth = UiMcpHealth.UNSUPPORTED
    suspend fun githubConfigured(): Boolean
    fun capabilityCatalogue(): List<UiToolCapabilitySnapshot>
    /** Revalidate against current registry registration; NEVER executes or authorizes tools. */
    fun validateCapabilityDraft(
        name: String, expectedSchema: kotlinx.serialization.json.JsonObject,
        argumentsJson: String,
    ): UiToolDraftValidation
    fun pluginLifecycleSnapshots(): List<UiPluginLifecycle> = emptyList()
    suspend fun recoverFailedPlugin(id: String): List<String> =
        error("当前工具 Owner 不支持插件恢复")
    fun installedPluginIds(): List<String>
    suspend fun installedSkills(): List<UiSkillSnapshot>
    suspend fun presetSkills(): List<UiPresetSkillSnapshot>
    suspend fun readSkillDocument(id: String): String
    suspend fun curatedGitHubSkills(): List<String>
    suspend fun installGitHubSkill(link: String): String
    suspend fun installPreset(id: String)
    suspend fun createSkill(id: String, displayName: String, description: String, instructions: String)
    suspend fun importSkill(filename: String, bytes: ByteArray): String
    suspend fun removeSkill(id: String)
    suspend fun updateSkillDocument(id: String, document: String)
    suspend fun setSkillModelInvocable(id: String, enabled: Boolean)
    suspend fun connectHttp(serverId: String, endpoint: String): String
    suspend fun connectStdio(serverId: String, command: List<String>, workingDirectory: String?): String
    suspend fun disconnect(serverId: String): String
    suspend fun configureGitHub(token: String)
    suspend fun clearGitHub()
}

sealed interface ToolsUiCommand {
    data class ConnectHttp(val serverId: String, val endpoint: String) : ToolsUiCommand
    data class ConnectStdio(val serverId: String, val command: List<String>, val workingDirectory: String?) : ToolsUiCommand
    data class Disconnect(val serverId: String) : ToolsUiCommand
    data class RecoverFailedPlugin(val id: String) : ToolsUiCommand
    class ConfigureGitHub(val token: String) : ToolsUiCommand {
        override fun toString() = "ConfigureGitHub(<redacted>)"
    }
    data object ClearGitHub : ToolsUiCommand
    data class InstallGitHubSkill(val url: String) : ToolsUiCommand
    data class InstallPreset(val id: String) : ToolsUiCommand
    data class CreateSkill(val id: String, val displayName: String, val description: String, val instructions: String) : ToolsUiCommand
    class ImportSkill(val filename: String, val bytes: ByteArray) : ToolsUiCommand {
        override fun toString() = "ImportSkill(<user file>)"
    }
    data class RemoveSkill(val id: String) : ToolsUiCommand
    data class UpdateSkill(val id: String, val document: String) : ToolsUiCommand
    data class SetSkillModelInvocable(val id: String, val enabled: Boolean) : ToolsUiCommand
}

enum class UiToolError { DNS, TIMEOUT, UNREACHABLE, PERMISSION, CONFIGURATION, UNKNOWN }
sealed interface ToolsUiReceipt {
    /** Configuration was actually applied by the owner; this never means an Agent run completed. */
    data class Applied(val ownerResult: String? = null) : ToolsUiReceipt
    data class Failed(val reason: UiToolError) : ToolsUiReceipt
}

fun classifyUiToolError(error: Throwable): UiToolError {
    var cause = error
    repeat(4) { cause.cause?.let { cause = it } }
    return when (cause) {
        is java.net.UnknownHostException -> UiToolError.DNS
        is java.net.SocketTimeoutException -> UiToolError.TIMEOUT
        is java.net.ConnectException -> UiToolError.UNREACHABLE
        is SecurityException -> UiToolError.PERMISSION
        is IllegalArgumentException -> UiToolError.CONFIGURATION
        else -> UiToolError.UNKNOWN
    }
}

/** Strong intents delegate to the existing management owner. No tool execution API is exposed. */
suspend fun ToolsUiPort.submit(command: ToolsUiCommand): ToolsUiReceipt = try {
    val result = when (command) {
        is ToolsUiCommand.ConnectHttp -> connectHttp(command.serverId, command.endpoint)
        is ToolsUiCommand.ConnectStdio -> connectStdio(command.serverId, command.command, command.workingDirectory)
        is ToolsUiCommand.Disconnect -> disconnect(command.serverId)
        is ToolsUiCommand.RecoverFailedPlugin -> recoverFailedPlugin(command.id).joinToString(",")
        is ToolsUiCommand.ConfigureGitHub -> { configureGitHub(command.token); null }
        ToolsUiCommand.ClearGitHub -> { clearGitHub(); null }
        is ToolsUiCommand.InstallGitHubSkill -> installGitHubSkill(command.url)
        is ToolsUiCommand.InstallPreset -> { installPreset(command.id); null }
        is ToolsUiCommand.CreateSkill -> { createSkill(command.id, command.displayName, command.description, command.instructions); null }
        is ToolsUiCommand.ImportSkill -> importSkill(command.filename, command.bytes)
        is ToolsUiCommand.RemoveSkill -> { removeSkill(command.id); null }
        is ToolsUiCommand.UpdateSkill -> { updateSkillDocument(command.id, command.document); null }
        is ToolsUiCommand.SetSkillModelInvocable -> { setSkillModelInvocable(command.id, command.enabled); null }
    }
    ToolsUiReceipt.Applied(result)
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    ToolsUiReceipt.Failed(classifyUiToolError(failure))
}
