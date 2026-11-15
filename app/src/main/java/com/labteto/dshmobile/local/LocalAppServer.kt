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
import com.labteto.dshmobile.uicontract.UiAppServerRead
import com.labteto.dshmobile.uicontract.UiAppServerState
import com.labteto.dshmobile.uicontract.UiQueuedUserPort
import com.labteto.dshmobile.uicontract.UiQueuedUserCommand
import com.labteto.dshmobile.uicontract.WorkAgentUiPort
import com.labteto.dshmobile.uicontract.WorkConversationUiPort
import com.labteto.dshmobile.uicontract.WorkReadUiPort
import com.labteto.dshmobile.uicontract.FilesUiPort
import com.labteto.dshmobile.uicontract.AttachmentUiPort
import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.save
import com.labteto.dshmobile.uicontract.submit
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** One feature manifest drawn directly from the authoritative Architecture 3.0 catalog. */
internal fun localAppServerManifest() = UiAppServerManifest(
    UI_CONTRACT_VERSION,
    LocalFeatureCatalog.modules.map { module ->
        UiAppServerEndpoint(
            UiAppServerFeature.valueOf(module.id.name),
            module.routes.map { it.name },
        )
    },
)

/** Android-local app-server: one discoverable API to existing Feature Ports, not a new daemon. */
@Singleton
internal class LocalAppServer @Inject constructor(
    private val chatOwner: Provider<ChatUiPort>,
    private val workOwner: Provider<WorkConversationUiPort>,
    private val sessionOwner: Provider<SessionUiPort>,
    private val agentOwner: Provider<WorkAgentUiPort>,
    private val automationOwner: Provider<AutomationUiPort>,
    private val toolsOwner: Provider<ToolsManagementUiPort>,
    private val settingsOwner: Provider<SettingsUiPort>,
    private val workReadOwner: Provider<WorkReadUiPort>,
    private val filesOwner: Provider<FilesUiPort>,
    private val attachmentsOwner: Provider<AttachmentUiPort>,
    private val queuedUserOwner: Provider<UiQueuedUserPort>,
) : AppServerUiPort {
    override val chat: ChatUiPort get() = chatOwner.get()
    override val work: WorkConversationUiPort get() = workOwner.get()
    override val session: SessionUiPort get() = sessionOwner.get()
    override val agents: WorkAgentUiPort get() = agentOwner.get()
    override val automation: AutomationUiPort get() = automationOwner.get()
    override val tools: ToolsManagementUiPort get() = toolsOwner.get()
    override val settings: SettingsUiPort get() = settingsOwner.get()
    override val workRead: WorkReadUiPort get() = workReadOwner.get()
    override val files: FilesUiPort get() = filesOwner.get()
    override val attachments: AttachmentUiPort get() = attachmentsOwner.get()
    override val queuedUsers: UiQueuedUserPort get() = queuedUserOwner.get()

    override fun manifest(): UiAppServerManifest = localAppServerManifest()

    override fun read(query: UiAppServerRead): UiAppServerState = when (query) {
        UiAppServerRead.Chat -> UiAppServerState.Chat(chat.snapshot())
        UiAppServerRead.Work -> UiAppServerState.Work(work.snapshot())
        UiAppServerRead.Sessions -> UiAppServerState.Sessions(session.snapshot())
        UiAppServerRead.Agents -> UiAppServerState.Agents(agents.snapshot())
        UiAppServerRead.QueuedUsers -> UiAppServerState.QueuedUsers(queuedUsers.snapshot())
        UiAppServerRead.Automations -> UiAppServerState.Automations(automation.snapshot())
        UiAppServerRead.Settings -> UiAppServerState.Settings(settings.initialState)
        is UiAppServerRead.Capabilities -> {
            require(query.limit > 0) { "能力目录读取数量须大于零" }
            val matches = if (query.query.isNullOrBlank()) tools.capabilityCatalogue()
                else tools.discoverCapabilities(query.query, query.limit)
            UiAppServerState.Capabilities(matches.take(query.limit))
        }
    }

    /** Subscriptions activate only their selected Feature Owners. */
    override val notices: Flow<UiAppServerNotice> = noticesFor(UiAppServerFeature.entries.toSet())

    override fun noticesFor(features: Set<UiAppServerFeature>): Flow<UiAppServerNotice> = flow {
        val selected: List<Flow<UiAppServerNotice>> = buildList {
            if (UiAppServerFeature.SHELL in features)
                add(session.snapshots.map { UiAppServerNotice(UiAppServerFeature.SHELL, it.identity) })
            if (UiAppServerFeature.CHAT in features)
                add(chat.snapshots.map { UiAppServerNotice(UiAppServerFeature.CHAT, it.identity) })
            if (UiAppServerFeature.WORK in features) {
                add(work.snapshots.map { UiAppServerNotice(UiAppServerFeature.WORK, it.identity) })
                add(agents.snapshots.map { UiAppServerNotice(UiAppServerFeature.WORK, it.identity) })
            }
            if (UiAppServerFeature.AUTOMATION in features)
                add(automation.snapshots.map {
                    UiAppServerNotice(UiAppServerFeature.AUTOMATION, it.context.identity)
                })
            if (UiAppServerFeature.TOOLS in features) {
                add(tools.serverChanges().map { UiAppServerNotice(UiAppServerFeature.TOOLS) })
                add(tools.skillChanges().map { UiAppServerNotice(UiAppServerFeature.TOOLS) })
                add(tools.networkSearchEnabled.map { UiAppServerNotice(UiAppServerFeature.TOOLS) })
                add(tools.deepSeekSearchPreferred.map { UiAppServerNotice(UiAppServerFeature.TOOLS) })
            }
            if (UiAppServerFeature.SETTINGS in features)
                add(settings.state.map { UiAppServerNotice(UiAppServerFeature.SETTINGS) })
        }
        if (selected.isNotEmpty()) emitAll(merge(*selected.toTypedArray()))
    }

    override suspend fun submit(command: UiAppServerCommand): UiAppServerReply = when (command) {
        is UiAppServerCommand.Chat -> UiAppServerReply.Chat(chat.submit(command.command))
        is UiAppServerCommand.Work -> UiAppServerReply.Work(work.submit(command.command))
        is UiAppServerCommand.Session -> UiAppServerReply.Session(session.submit(command.command))
        is UiAppServerCommand.Agent -> UiAppServerReply.Agent(agents.submit(command.command))
        is UiAppServerCommand.Automation -> UiAppServerReply.Automation(automation.submit(command.command))
        is UiAppServerCommand.Tools -> UiAppServerReply.Tools(tools.submit(command.command))
        is UiAppServerCommand.Queue -> UiAppServerReply.Queue(when (val action = command.command) {
            is UiQueuedUserCommand.Edit -> queuedUsers.edit(action.identity, action.messageId, action.text)
            is UiQueuedUserCommand.Remove -> queuedUsers.remove(action.identity, action.messageId)
            is UiQueuedUserCommand.Reorder -> queuedUsers.reorder(action.identity, action.orderedIds)
        })
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
