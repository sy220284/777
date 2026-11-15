package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.feature.LocalFeatureCatalog
import com.labteto.dshmobile.local.feature.LocalFeatureRoute
import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Provider
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import com.labteto.dshmobile.uicontract.AppServerUiPort
import com.labteto.dshmobile.uicontract.ChatUiPort
import com.labteto.dshmobile.uicontract.ChatUiCommand
import com.labteto.dshmobile.uicontract.WorkConversationUiPort
import com.labteto.dshmobile.uicontract.WorkConversationUiCommand
import com.labteto.dshmobile.uicontract.SessionUiPort
import com.labteto.dshmobile.uicontract.SessionUiCommand
import com.labteto.dshmobile.uicontract.WorkAgentUiPort
import com.labteto.dshmobile.uicontract.WorkTeamUiCommand
import com.labteto.dshmobile.uicontract.UiWorkTeamResult
import com.labteto.dshmobile.uicontract.AutomationUiPort
import com.labteto.dshmobile.uicontract.AutomationUiCommand
import com.labteto.dshmobile.uicontract.AutomationUiReceipt
import com.labteto.dshmobile.uicontract.ToolsManagementUiPort
import com.labteto.dshmobile.uicontract.ToolsUiCommand
import com.labteto.dshmobile.uicontract.ToolsUiReceipt
import com.labteto.dshmobile.uicontract.SettingsUiPort
import com.labteto.dshmobile.uicontract.SettingsUiCommand
import com.labteto.dshmobile.uicontract.SettingsUiReceipt
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.UiAppServerCommand
import com.labteto.dshmobile.uicontract.UiAppServerReply
import com.labteto.dshmobile.uicontract.MediaUiPort
import com.labteto.dshmobile.uicontract.MediaUiCommand
import com.labteto.dshmobile.uicontract.MediaUiReceipt
import com.labteto.dshmobile.uicontract.UiQueuedUserCommand
import com.labteto.dshmobile.uicontract.UiQueuedUserPort
import com.labteto.dshmobile.uicontract.UiQueuedUserSnapshot
import com.labteto.dshmobile.uicontract.UiAppServerRead
import com.labteto.dshmobile.uicontract.UiAppServerState
import com.labteto.dshmobile.uicontract.UiSettingsSnapshot
import com.labteto.dshmobile.uicontract.UiAppServerFeature
import org.junit.Assert.assertThrows
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import com.labteto.dshmobile.uicontract.UsageUiPort
import com.labteto.dshmobile.uicontract.PricingUiPort
import com.labteto.dshmobile.uicontract.UiProviderPricingSnapshot

class LocalAppServerManifestTest {
    @Test fun settingsSubscriptionIncludesModelUsageAndPricingOwnerChanges() = runBlocking {
        val subscribed = mutableListOf<String>()
        val settings = java.lang.reflect.Proxy.newProxyInstance(
            SettingsUiPort::class.java.classLoader, arrayOf(SettingsUiPort::class.java)
        ) { _, method, _ ->
            subscribed += "settings." + method.name
            when (method.name) {
                "getState" -> flowOf(UiSettingsSnapshot())
                else -> error("unexpected settings lookup " + method.name)
            }
        } as SettingsUiPort
        val usage = java.lang.reflect.Proxy.newProxyInstance(
            UsageUiPort::class.java.classLoader, arrayOf(UsageUiPort::class.java)
        ) { _, method, _ ->
            subscribed += "usage." + method.name
            when (method.name) {
                "getRevision" -> MutableStateFlow(3L)
                else -> error("unexpected usage lookup " + method.name)
            }
        } as UsageUiPort
        val pricing = java.lang.reflect.Proxy.newProxyInstance(
            PricingUiPort::class.java.classLoader, arrayOf(PricingUiPort::class.java)
        ) { _, method, _ ->
            subscribed += "pricing." + method.name
            when (method.name) {
                "getSnapshots" -> flowOf(UiProviderPricingSnapshot())
                else -> error("unexpected pricing lookup " + method.name)
            }
        } as PricingUiPort
        val server = LocalAppServer(
            chatOwner = Provider { error("Unexpected Chat") },
            workOwner = Provider { error("Unexpected Work") },
            sessionOwner = Provider { error("Unexpected Session") },
            agentOwner = Provider { error("Unexpected Agents") },
            automationOwner = Provider { error("Unexpected Automation") },
            toolsOwner = Provider { error("Unexpected Tools") },
            settingsOwner = Provider { settings },
            filesOwner = Provider { error("Unexpected Files") },
            attachmentsOwner = Provider { error("Unexpected Attachments") },
            queuedUserOwner = Provider { error("Unexpected Queue") },
            mediaOwner = Provider { error("Unexpected Media") },
            usageOwner = Provider { usage },
            memoryOwner = Provider { error("Unexpected Memory") },
            pricingOwner = Provider { pricing },
            workReadOwner = Provider { error("Unexpected WorkRead") },
        )
        val notices = server.noticesFor(setOf(UiAppServerFeature.SETTINGS))
        assertTrue(subscribed.isEmpty())
        assertEquals(3, notices.take(3).toList().size)
        assertEquals(
            setOf("settings.getState", "usage.getRevision", "pricing.getSnapshots"),
            subscribed.toSet(),
        )
    }


    @Test fun mediaCommandsAreDelegatedWithoutStartingUnrelatedFeatures() = runBlocking {
        val called = mutableListOf<String>()
        val mediaPort = java.lang.reflect.Proxy.newProxyInstance(
            MediaUiPort::class.java.classLoader, arrayOf(MediaUiPort::class.java)
        ) { _, method, args ->
            called += method.name
            if (method.name == "submit") {
                assertEquals(MediaUiCommand.RetryStorage, args?.firstOrNull())
                MediaUiReceipt.Applied
            } else error("Unexpected media method " + method.name)
        } as MediaUiPort
        val server = LocalAppServer(
            chatOwner = Provider { error("Unexpected Chat") },
            workOwner = Provider { error("Unexpected Work") },
            sessionOwner = Provider { error("Unexpected Session") },
            agentOwner = Provider { error("Unexpected Agents") },
            automationOwner = Provider { error("Unexpected Automation") },
            toolsOwner = Provider { error("Unexpected Tools") },
            settingsOwner = Provider { error("Unexpected Settings") },
            filesOwner = Provider { error("Unexpected Files") },
            attachmentsOwner = Provider { error("Unexpected Attachments") },
            queuedUserOwner = Provider { error("Unexpected Queue") },
            mediaOwner = Provider { mediaPort },
            usageOwner = Provider { error("Unexpected Usage") },
            memoryOwner = Provider { error("Unexpected Memory") },
            pricingOwner = Provider { error("Unexpected Pricing") },
            workReadOwner = Provider { error("Unexpected WorkRead") },
        )
        assertEquals(
            UiAppServerReply.Media(MediaUiReceipt.Applied),
            server.submit(UiAppServerCommand.Media(MediaUiCommand.RetryStorage)),
        )
        assertEquals(listOf("submit"), called)
    }


    @Test fun queueReadAndCommandsUseExistingOwnerOnlyWithExactIdentity() = runBlocking {
        val identity = UiSessionIdentity("queue-session", "active", 2L, null, null)
        val calls = mutableListOf<String>()
        val queuePort = java.lang.reflect.Proxy.newProxyInstance(
            UiQueuedUserPort::class.java.classLoader, arrayOf(UiQueuedUserPort::class.java)
        ) { _, method, args ->
            calls += method.name
            when (method.name) {
                "snapshot" -> UiQueuedUserSnapshot(identity, emptyList())
                "edit", "remove", "reorder" -> {
                    assertEquals(identity, args?.firstOrNull())
                    UiCommandReceipt.Accepted
                }
                else -> error("unexpected queue method ${method.name}")
            }
        } as UiQueuedUserPort
        val server = LocalAppServer(
            chatOwner = Provider { error("Chat must stay dormant") },
            workOwner = Provider { error("Work must stay dormant") },
            sessionOwner = Provider { error("Session must stay dormant") },
            agentOwner = Provider { error("Agent must stay dormant") },
            automationOwner = Provider { error("Automation must stay dormant") },
            toolsOwner = Provider { error("Tools must stay dormant") },
            settingsOwner = Provider { error("Settings must stay dormant") },
            filesOwner = Provider { error("Files must stay dormant") },
            attachmentsOwner = Provider { error("Attachments must stay dormant") },
            queuedUserOwner = Provider { queuePort },
            mediaOwner = Provider { error("Media owner must stay lazy") },
            usageOwner = Provider { error("Usage owner must stay lazy") },
            memoryOwner = Provider { error("Memory owner must stay lazy") },
            pricingOwner = Provider { error("Pricing owner must stay lazy") },
            workReadOwner = Provider { error("Work read must stay dormant") },
        )
        assertEquals(
            UiAppServerState.QueuedUsers(UiQueuedUserSnapshot(identity, emptyList())),
            server.read(UiAppServerRead.QueuedUsers),
        )
        assertEquals(
            UiAppServerReply.Queue(UiCommandReceipt.Accepted),
            server.submit(UiAppServerCommand.Queue(UiQueuedUserCommand.Edit(identity, "q1", "rewrite"))),
        )
        assertEquals(
            UiAppServerReply.Queue(UiCommandReceipt.Accepted),
            server.submit(UiAppServerCommand.Queue(UiQueuedUserCommand.Remove(identity, "q1"))),
        )
        assertEquals(
            UiAppServerReply.Queue(UiCommandReceipt.Accepted),
            server.submit(UiAppServerCommand.Queue(UiQueuedUserCommand.Reorder(identity, listOf("q2", "q1")))),
        )
        assertEquals(listOf("snapshot", "edit", "remove", "reorder"), calls)
    }


    @Test fun scopedNoticesActivateOnlyRequestedFeatureOwner() = runBlocking {
        val calls = mutableListOf<String>()
        val tools = Proxy.newProxyInstance(
            ToolsManagementUiPort::class.java.classLoader, arrayOf(ToolsManagementUiPort::class.java)
        ) { _, method, _ ->
            calls += method.name
            when (method.name) {
                "serverChanges", "skillChanges" -> flowOf(Unit)
                "getNetworkSearchEnabled", "getDeepSeekSearchPreferred" -> MutableStateFlow(false)
                else -> error("Unexpected tool callback " + method.name)
            }
        } as ToolsManagementUiPort
        val server = LocalAppServer(
            chatOwner = Provider { error("Chat initialized unexpectedly") },
            workOwner = Provider { error("Work initialized unexpectedly") },
            sessionOwner = Provider { error("Session initialized unexpectedly") },
            agentOwner = Provider { error("Agent initialized unexpectedly") },
            automationOwner = Provider { error("Automation initialized unexpectedly") },
            toolsOwner = Provider { tools },
            settingsOwner = Provider { error("Settings initialized unexpectedly") },
            filesOwner = Provider { error("Files owner must stay lazy") },
            attachmentsOwner = Provider { error("Attachment owner must stay lazy") },
            queuedUserOwner = Provider { error("Queue owner must stay lazy") },
            mediaOwner = Provider { error("Media owner must stay lazy") },
            usageOwner = Provider { error("Usage owner must stay lazy") },
            memoryOwner = Provider { error("Memory owner must stay lazy") },
            pricingOwner = Provider { error("Pricing owner must stay lazy") },
            workReadOwner = Provider { error("WorkRead initialized unexpectedly") },
        )
        val stream = server.noticesFor(setOf(UiAppServerFeature.TOOLS))
        assertTrue(calls.isEmpty())
        assertEquals(UiAppServerFeature.TOOLS, stream.first().feature)
        assertTrue("serverChanges" in calls)
    }


    @Test fun typedReadQueriesOnlyActivateRequestedOwner() {
        val activated = mutableListOf<String>()
        val invoked = mutableListOf<String>()
        val settingsSnapshot = UiSettingsSnapshot(model = "test-model")
        val settings = Proxy.newProxyInstance(
            SettingsUiPort::class.java.classLoader, arrayOf(SettingsUiPort::class.java)
        ) { _, method, _ ->
            invoked += "settings." + method.name
            when (method.name) {
                "getInitialState" -> settingsSnapshot
                else -> error("Unexpected Settings method: " + method.name)
            }
        } as SettingsUiPort
        val tools = Proxy.newProxyInstance(
            ToolsManagementUiPort::class.java.classLoader, arrayOf(ToolsManagementUiPort::class.java)
        ) { _, method, args ->
            invoked += "tools." + method.name
            when (method.name) {
                "capabilityCatalogue" -> emptyList<com.labteto.dshmobile.uicontract.UiToolCapabilitySnapshot>()
                "discoverCapabilities" -> emptyList<com.labteto.dshmobile.uicontract.UiToolCapabilitySnapshot>()
                else -> error("Unexpected Tools method: " + method.name)
            }
        } as ToolsManagementUiPort
        val server = LocalAppServer(
            chatOwner = Provider { error("Chat owner accessed by another Feature") },
            workOwner = Provider { error("Work owner accessed by another Feature") },
            sessionOwner = Provider { error("Session owner accessed by another Feature") },
            agentOwner = Provider { error("Agent owner accessed by another Feature") },
            automationOwner = Provider { error("Automation owner accessed by another Feature") },
            toolsOwner = Provider { activated += "tools"; tools },
            settingsOwner = Provider { activated += "settings"; settings },
            filesOwner = Provider { error("Files owner must stay lazy") },
            attachmentsOwner = Provider { error("Attachment owner must stay lazy") },
            queuedUserOwner = Provider { error("Queue owner must stay lazy") },
            mediaOwner = Provider { error("Media owner must stay lazy") },
            usageOwner = Provider { error("Usage owner must stay lazy") },
            memoryOwner = Provider { error("Memory owner must stay lazy") },
            pricingOwner = Provider { error("Pricing owner must stay lazy") },
            workReadOwner = Provider { error("Work Read owner accessed by another Feature") },
        )
        assertEquals(UiAppServerState.Settings(settingsSnapshot), server.read(UiAppServerRead.Settings))
        assertEquals(listOf("settings"), activated)
        assertEquals(listOf("settings.getInitialState"), invoked)
        assertEquals(UiAppServerState.Capabilities(emptyList()), server.read(
            UiAppServerRead.Capabilities("android", 10),
        ))
        assertEquals(listOf("settings", "tools"), activated)
        assertEquals(listOf("settings.getInitialState", "tools.discoverCapabilities"), invoked)
        assertThrows(IllegalArgumentException::class.java) {
            server.read(UiAppServerRead.Capabilities("android", 0))
        }
        assertEquals(2, activated.size)
    }


    @Test fun allCommandsDelegateToOneExactOwnerWithoutStartingOtherFeatures() = runBlocking {
        val activated = mutableListOf<String>()
        val invoked = mutableListOf<String>()
        fun <T> owner(name: String, target: T): Provider<T> = Provider {
            activated += name
            target
        }

        val chat = proxy<ChatUiPort>("chat", invoked, UiCommandReceipt.Accepted)
        val work = proxy<WorkConversationUiPort>("work", invoked, UiCommandReceipt.Queued)
        val session = proxy<SessionUiPort>("session", invoked, UiCommandReceipt.Accepted)
        val teamReceipt = UiWorkTeamResult(UiCommandReceipt.Accepted, "任务已受理")
        val agents = proxy<WorkAgentUiPort>("agents", invoked, teamReceipt)
        val automation = proxy<AutomationUiPort>("automation", invoked, AutomationUiReceipt.Applied("task"))
        val tools = proxy<ToolsManagementUiPort>("tools", invoked, Unit)
        val settings = proxy<SettingsUiPort>("settings", invoked, Unit)

        val server = LocalAppServer(
            chatOwner = owner("chat", chat),
            workOwner = owner("work", work),
            sessionOwner = owner("session", session),
            agentOwner = owner("agents", agents),
            automationOwner = owner("automation", automation),
            toolsOwner = owner("tools", tools),
            settingsOwner = owner("settings", settings),
            filesOwner = Provider { error("Files owner must stay lazy") },
            attachmentsOwner = Provider { error("Attachment owner must stay lazy") },
            queuedUserOwner = Provider { error("Queue owner must stay lazy") },
            mediaOwner = Provider { error("Media owner must stay lazy") },
            usageOwner = Provider { error("Usage owner must stay lazy") },
            memoryOwner = Provider { error("Memory owner must stay lazy") },
            pricingOwner = Provider { error("Pricing owner must stay lazy") },
            workReadOwner = Provider { error("reading owner must remain lazy") },
        )
        // Manifest and notification-flow acquisition must have no hidden initialization.
        server.manifest()
        server.notices
        assertTrue(activated.isEmpty())

        val identity = UiSessionIdentity("s", "activation", 1L, null, null)
        suspend fun check(
            command: UiAppServerCommand,
            expected: UiAppServerReply,
            owner: String,
            operation: String,
        ) {
            val oldActivated = activated.size
            val oldInvoked = invoked.size
            assertEquals(expected, server.submit(command))
            assertEquals(listOf(owner), activated.drop(oldActivated))
            assertEquals(listOf("$owner.$operation"), invoked.drop(oldInvoked))
        }

        check(
            UiAppServerCommand.Chat(ChatUiCommand.SendText(identity, "hi")),
            UiAppServerReply.Chat(UiCommandReceipt.Accepted), "chat", "submit",
        )
        check(
            UiAppServerCommand.Work(WorkConversationUiCommand.SendText(identity, "run")),
            UiAppServerReply.Work(UiCommandReceipt.Queued), "work", "submit",
        )
        check(
            UiAppServerCommand.Session(SessionUiCommand.Open("s")),
            UiAppServerReply.Session(UiCommandReceipt.Accepted), "session", "submit",
        )
        check(
            UiAppServerCommand.Agent(WorkTeamUiCommand.StopTeam(identity, emptySet())),
            UiAppServerReply.Agent(teamReceipt), "agents", "submit",
        )
        check(
            UiAppServerCommand.Automation(AutomationUiCommand.Delete("task")),
            UiAppServerReply.Automation(AutomationUiReceipt.Applied("task")), "automation", "submit",
        )
        check(
            UiAppServerCommand.Tools(ToolsUiCommand.ClearGitHub),
            UiAppServerReply.Tools(ToolsUiReceipt.Applied()), "tools", "clearGitHub",
        )
        check(
            UiAppServerCommand.Settings(SettingsUiCommand.ClearCredential),
            UiAppServerReply.Settings(SettingsUiReceipt.Submitted), "settings", "clearCredential",
        )
        check(
            UiAppServerCommand.Settings(SettingsUiCommand.SaveModel("secret", "test", "https://test.invalid")),
            UiAppServerReply.Settings(SettingsUiReceipt.Applied), "settings", "saveModel",
        )
    }

    @Test fun cancellationFromSettingsOwnerIsNotConvertedIntoSuccessfulReceipt() {
        val port = proxy<SettingsUiPort>("settings", mutableListOf(), Unit) {
            throw kotlinx.coroutines.CancellationException("Owner cancellation")
        }
        val server = LocalAppServer(
            chatOwner = Provider { error("chat was not requested") },
            workOwner = Provider { error("work was not requested") },
            sessionOwner = Provider { error("session was not requested") },
            agentOwner = Provider { error("agent was not requested") },
            automationOwner = Provider { error("automation was not requested") },
            toolsOwner = Provider { error("tools was not requested") },
            settingsOwner = Provider { port },
            filesOwner = Provider { error("Files owner must stay lazy") },
            attachmentsOwner = Provider { error("Attachment owner must stay lazy") },
            queuedUserOwner = Provider { error("Queue owner must stay lazy") },
            mediaOwner = Provider { error("Media owner must stay lazy") },
            usageOwner = Provider { error("Usage owner must stay lazy") },
            memoryOwner = Provider { error("Memory owner must stay lazy") },
            pricingOwner = Provider { error("Pricing owner must stay lazy") },
            workReadOwner = Provider { error("read was not requested") },
        )
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { server.submit(UiAppServerCommand.Settings(SettingsUiCommand.ClearCredential)) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> proxy(
        owner: String,
        invoked: MutableList<String>,
        response: Any?,
        crossinline custom: () -> Any? = { response },
    ): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        invoked += "$owner.${method.name}"
        custom()
    } as T

    @Test fun readingManifestDoesNotInitializeAnyFeatureOwner() {
        val server = LocalAppServer(
            chatOwner = Provider { error("chat unexpectedly initialized") },
            workOwner = Provider { error("work unexpectedly initialized") },
            sessionOwner = Provider { error("session unexpectedly initialized") },
            agentOwner = Provider { error("agent unexpectedly initialized") },
            automationOwner = Provider { error("automation unexpectedly initialized") },
            toolsOwner = Provider { error("tools unexpectedly initialized") },
            settingsOwner = Provider { error("settings unexpectedly initialized") },
            filesOwner = Provider { error("Files owner must stay lazy") },
            attachmentsOwner = Provider { error("Attachment owner must stay lazy") },
            queuedUserOwner = Provider { error("Queue owner must stay lazy") },
            mediaOwner = Provider { error("Media owner must stay lazy") },
            usageOwner = Provider { error("Usage owner must stay lazy") },
            memoryOwner = Provider { error("Memory owner must stay lazy") },
            pricingOwner = Provider { error("Pricing owner must stay lazy") },
            workReadOwner = Provider { error("work read unexpectedly initialized") },
        )
        assertEquals(6, server.manifest().endpoints.size)
        // A request for one feature does not cause eager initialization of the other six.
    }

    @Test fun manifestHasExactlyOneOwnerForEveryProductRoute() {
        val manifest = localAppServerManifest()
        assertEquals(UI_CONTRACT_VERSION, manifest.version)
        assertEquals(UiAppServerFeature.entries.toSet(), manifest.endpoints.map { it.feature }.toSet())
        assertEquals(LocalFeatureCatalog.modules.size, manifest.endpoints.size)
        assertEquals(LocalFeatureRoute.entries.map { it.name }.toSet(),
            manifest.endpoints.flatMap { it.routes }.toSet())
        assertEquals(LocalFeatureRoute.entries.size,
            manifest.endpoints.flatMap { it.routes }.size)
        assertTrue(manifest.endpoints.all { it.routes.isNotEmpty() })
    }
}
