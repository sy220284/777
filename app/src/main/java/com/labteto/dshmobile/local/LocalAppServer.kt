package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.feature.LocalFeatureCatalog
import com.labteto.dshmobile.uicontract.AppServerUiPort
import com.labteto.dshmobile.uicontract.AutomationUiPort
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.SessionUiPort
import com.labteto.dshmobile.uicontract.SettingsUiPort
import com.labteto.dshmobile.uicontract.SettingsUiCommand
import com.labteto.dshmobile.uicontract.ToolsManagementUiPort
import com.labteto.dshmobile.uicontract.UiAppServerEndpoint
import com.labteto.dshmobile.uicontract.UiAppServerFeature
import com.labteto.dshmobile.uicontract.UiAppServerManifest
import com.labteto.dshmobile.uicontract.UiAppServerNotice
import com.labteto.dshmobile.uicontract.UiAppServerCommand
import com.labteto.dshmobile.uicontract.UiAppServerReply
import com.labteto.dshmobile.uicontract.WorkAgentUiPort
import com.labteto.dshmobile.uicontract.WorkConversationUiPort
import com.labteto.dshmobile.uicontract.WorkReadUiPort
import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.save
import com.labteto.dshmobile.uicontract.submit
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Android-local app-server: one discoverable API to existing Feature Ports, not a new daemon. */
@Singleton
internal class LocalAppServer @Inject constructor(
    conversation: LocalConversationUiPortComposition,
    override val session: SessionUiPort,
    override val agents: WorkAgentUiPort,
    override val automation: AutomationUiPort,
    override val tools: ToolsManagementUiPort,
    override val settings: SettingsUiPort,
    override val workRead: WorkReadUiPort,
) : AppServerUiPort {
    override val chat: ChatUiPort = conversation.chat
    override val work: WorkConversationUiPort = conversation.work

    override fun manifest() = UiAppServerManifest(
        UI_CONTRACT_VERSION,
        LocalFeatureCatalog.modules.map { module ->
            UiAppServerEndpoint(
                UiAppServerFeature.valueOf(module.id.name),
                module.routes.map { it.name },
            )
        },
    )

    override val notices = merge(
        chat.snapshots.map { UiAppServerNotice(UiAppServerFeature.CHAT, it.identity) },
        work.snapshots.map { UiAppServerNotice(UiAppServerFeature.WORK, it.identity) },
        session.snapshots.map { UiAppServerNotice(UiAppServerFeature.SHELL, it.identity) },
        agents.snapshots.map { UiAppServerNotice(UiAppServerFeature.WORK, it.identity) },
        automation.snapshots.map { UiAppServerNotice(UiAppServerFeature.AUTOMATION, it.context.identity) },
        tools.serverChanges().map { UiAppServerNotice(UiAppServerFeature.TOOLS) },
        tools.skillChanges().map { UiAppServerNotice(UiAppServerFeature.TOOLS) },
        tools.networkSearchEnabled.map { UiAppServerNotice(UiAppServerFeature.TOOLS) },
        tools.deepSeekSearchPreferred.map { UiAppServerNotice(UiAppServerFeature.TOOLS) },
        settings.state.map { UiAppServerNotice(UiAppServerFeature.SETTINGS) },
    )

    override suspend fun submit(command: UiAppServerCommand): UiAppServerReply = when (command) {
        is UiAppServerCommand.Chat -> UiAppServerReply.Chat(chat.submit(command.command))
        is UiAppServerCommand.Work -> UiAppServerReply.Work(work.submit(command.command))
        is UiAppServerCommand.Session -> UiAppServerReply.Session(session.submit(command.command))
        is UiAppServerCommand.Agent -> UiAppServerReply.Agent(agents.submit(command.command))
        is UiAppServerCommand.Automation -> UiAppServerReply.Automation(automation.submit(command.command))
        is UiAppServerCommand.Tools -> UiAppServerReply.Tools(tools.submit(command.command))
        is UiAppServerCommand.Settings -> UiAppServerReply.Settings(
            when (val request = command.command) {
                is SettingsUiCommand.SaveModel -> settings.save(request)
                else -> settings.submit(request)
            },
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LocalAppServerBindings {
    @Binds @Singleton
    abstract fun bind(implementation: LocalAppServer): AppServerUiPort
}
