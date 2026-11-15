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
    /** Notifications invalidate owner snapshots; no shadow EventLog or global sequence is created. */
    val notices: Flow<UiAppServerNotice>
    suspend fun submit(command: UiAppServerCommand): UiAppServerReply
}
