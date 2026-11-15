package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.Flow

/** Stable client API over existing Feature owners: no second Agent, Tool or Session executor. */
enum class UiAppServerFeature { SHELL, CHAT, WORK, AUTOMATION, TOOLS, SETTINGS }
data class UiAppServerEndpoint(val feature: UiAppServerFeature, val routes: List<String>)
data class UiAppServerManifest(val version: Int, val endpoints: List<UiAppServerEndpoint>)
data class UiAppServerNotice(val feature: UiAppServerFeature, val identity: UiSessionIdentity? = null)

/** Typed equivalents of Codex app-server method/params and method/result. */
sealed interface UiAppServerCommand {
    data class Chat(val command: ChatUiCommand) : UiAppServerCommand
    data class Work(val command: WorkConversationUiCommand) : UiAppServerCommand
    data class Session(val command: SessionUiCommand) : UiAppServerCommand
    data class Agent(val command: WorkTeamUiCommand) : UiAppServerCommand
    data class Automation(val command: AutomationUiCommand) : UiAppServerCommand
    data class Tools(val command: ToolsUiCommand) : UiAppServerCommand
    data class Settings(val command: SettingsUiCommand) : UiAppServerCommand
}
sealed interface UiAppServerReply {
    data class Chat(val receipt: UiCommandReceipt) : UiAppServerReply
    data class Work(val receipt: UiCommandReceipt) : UiAppServerReply
    data class Session(val receipt: UiCommandReceipt) : UiAppServerReply
    data class Agent(val receipt: UiWorkTeamResult) : UiAppServerReply
    data class Automation(val receipt: AutomationUiReceipt) : UiAppServerReply
    data class Tools(val receipt: ToolsUiReceipt) : UiAppServerReply
    data class Settings(val receipt: SettingsUiReceipt) : UiAppServerReply
}

/** Typed query envelope: one App Server entry for state reads, same owner-issued snapshots. */
sealed interface UiAppServerRead {
    data object Chat : UiAppServerRead
    data object Work : UiAppServerRead
    data object Sessions : UiAppServerRead
    data object Agents : UiAppServerRead
    data object Automations : UiAppServerRead
    data object Settings : UiAppServerRead
    /** Null query lists registered capabilities, nonblank query uses live discovery ranking. */
    data class Capabilities(val query: String? = null, val limit: Int = 50) : UiAppServerRead
}
sealed interface UiAppServerState {
    data class Chat(val snapshot: ChatSceneSnapshot) : UiAppServerState
    data class Work(val snapshot: WorkConversationSceneSnapshot) : UiAppServerState
    data class Sessions(val snapshot: UiSessionsSnapshot) : UiAppServerState
    data class Agents(val snapshot: UiWorkTeamSnapshot) : UiAppServerState
    data class Automations(val snapshot: UiAutomationSnapshot) : UiAppServerState
    data class Settings(val snapshot: UiSettingsSnapshot) : UiAppServerState
    data class Capabilities(val registered: List<UiToolCapabilitySnapshot>) : UiAppServerState
}

/** Single in-process Android client endpoint. The exposed ports are the one existing owners. */
interface AppServerUiPort {
    val chat: ChatUiPort
    val work: WorkConversationUiPort
    val session: SessionUiPort
    val agents: WorkAgentUiPort
    val automation: AutomationUiPort
    val tools: ToolsManagementUiPort
    val settings: SettingsUiPort
    val workRead: WorkReadUiPort
    fun manifest(): UiAppServerManifest
    /** Read one Feature owner without activating any unrelated owner. */
    fun read(query: UiAppServerRead): UiAppServerState
    /** Notifications invalidate owner snapshots; no shadow EventLog or global sequence is created. */
    val notices: Flow<UiAppServerNotice>
    suspend fun submit(command: UiAppServerCommand): UiAppServerReply
}
