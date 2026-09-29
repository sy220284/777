package com.labteto.dshmobile.local.tools

import android.content.Context
import com.labteto.dshmobile.automation.AutomationPlugin
import com.labteto.dshmobile.automation.AutomationStore
import com.labteto.dshmobile.automation.HarnessAutomationScheduler
import com.labteto.dshmobile.automation.WebhookController
import com.labteto.dshmobile.automation.WebhookPlugin
import com.labteto.dshmobile.device.AndroidDevicePlugin
import com.labteto.dshmobile.device.AndroidDeviceProvider
import com.labteto.dshmobile.harness.capability.HarnessVirtualDisplayProvider
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.plugin.PluginLifecycleSnapshot
import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.interop.github.GitHubConnectorPlugin
import com.labteto.dshmobile.interop.github.GitHubConnectorStatus
import com.labteto.dshmobile.interop.lsp.LspPlugin
import com.labteto.dshmobile.interop.mcp.McpServerSnapshot
import com.labteto.dshmobile.interop.mcp.McpToolBridgePlugin
import com.labteto.dshmobile.local.LocalApiKeyStore
import com.labteto.dshmobile.local.LocalBuiltinPlugin
import com.labteto.dshmobile.local.LocalToolCall
import com.labteto.dshmobile.local.LocalVisionPlugin
import com.labteto.dshmobile.local.LocalVisionRoute
import com.labteto.dshmobile.local.VisionClient
import com.labteto.dshmobile.runtime.AndroidProcessRuntime
import com.labteto.dshmobile.runtime.AndroidRuntimePlugin
import com.labteto.dshmobile.runtime.PersistentPipeTerminalProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Composition root for local Harness plugins.
 *
 * Platform-specific construction and plugin lifecycle stay here so LocalHarnessEngine can focus on
 * turn/session orchestration. Downstream agent code only receives capability contracts.
 */
@Singleton
class LocalPluginCompositionFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiKeys: LocalApiKeyStore,
    private val visionClient: VisionClient,
    private val githubCredentials: LocalGitHubCredentialStore,
    private val http: OkHttpClient,
    private val json: Json,
    private val automationScheduler: HarnessAutomationScheduler,
    private val automationStore: AutomationStore,
    private val webhookController: WebhookController,
) {
    internal fun create(
        workspaceRoot: File,
        runtimeProcess: AndroidProcessRuntime,
        runtimeTerminal: PersistentPipeTerminalProvider,
        languageServerCommand: (String?) -> List<String>,
        resourceScheduler: HarnessResourceScheduler,
        routeProvider: () -> LocalVisionRoute?,
        imageSupportProvider: (LocalVisionRoute) -> Boolean?,
        executeBuiltin: suspend (LocalToolCall, Boolean, String?) -> String,
    ): LocalPluginComposition = LocalPluginComposition(
        context = context,
        apiKeys = apiKeys,
        visionClient = visionClient,
        githubCredentials = githubCredentials,
        http = http,
        json = json,
        automationScheduler = automationScheduler,
        automationStore = automationStore,
        webhookController = webhookController,
        workspaceRoot = workspaceRoot,
        runtimeProcess = runtimeProcess,
        runtimeTerminal = runtimeTerminal,
        languageServerCommand = languageServerCommand,
        resourceScheduler = resourceScheduler,
        routeProvider = routeProvider,
        imageSupportProvider = imageSupportProvider,
        executeBuiltin = executeBuiltin,
    )
}

internal class LocalPluginComposition(
    context: Context,
    apiKeys: LocalApiKeyStore,
    visionClient: VisionClient,
    githubCredentials: LocalGitHubCredentialStore,
    http: OkHttpClient,
    json: Json,
    automationScheduler: HarnessAutomationScheduler,
    automationStore: AutomationStore,
    webhookController: WebhookController,
    workspaceRoot: File,
    runtimeProcess: AndroidProcessRuntime,
    runtimeTerminal: PersistentPipeTerminalProvider,
    languageServerCommand: (String?) -> List<String>,
    resourceScheduler: HarnessResourceScheduler,
    routeProvider: () -> LocalVisionRoute?,
    imageSupportProvider: (LocalVisionRoute) -> Boolean?,
    executeBuiltin: suspend (LocalToolCall, Boolean, String?) -> String,
) {
    val tools = ToolRegistry()
    private val registry = PluginRegistry(HarnessContext(tools = tools))

    private val runtimePlugin = AndroidRuntimePlugin(
        workspaceRoot = workspaceRoot,
        processRuntime = runtimeProcess,
        terminalProvider = runtimeTerminal,
        resourceScheduler = resourceScheduler,
    )
    private val mcpPlugin = McpToolBridgePlugin(
        http = http,
        json = json,
        workspaceRoot = workspaceRoot,
        stdioCommandResolver = runtimeProcess::resolveCommand,
        stdioEnvironmentProvider = { runtimeProcess.processEnvironment() },
    )
    private val githubPlugin = GitHubConnectorPlugin(
        http = http,
        json = json,
        credentialProvider = githubCredentials::get,
    )
    private val lspPlugin = LspPlugin(
        root = workspaceRoot,
        json = json,
        command = languageServerCommand,
        commandResolver = runtimeProcess::resolveCommand,
        environment = { runtimeProcess.processEnvironment() },
        resourceScheduler = resourceScheduler,
    )
    private val deviceProvider = AndroidDeviceProvider(
        context = context,
        resourceScheduler = resourceScheduler,
    )
    private val devicePlugin = AndroidDevicePlugin(deviceProvider)
    private val visionPlugin = LocalVisionPlugin(
        device = deviceProvider,
        keyProvider = apiKeys::get,
        routeProvider = routeProvider,
        analyzer = visionClient,
        workspaceRoot = workspaceRoot,
        imageSupportProvider = imageSupportProvider,
    )
    private val automationPlugin = AutomationPlugin(automationScheduler, automationStore)
    private val webhookPlugin = WebhookPlugin(webhookController)
    private val builtinPlugin = LocalBuiltinPlugin(executeBuiltin)

    val virtualDisplayProvider: HarnessVirtualDisplayProvider
        get() = deviceProvider

    private val startupPlugins: List<HarnessPlugin> = listOf(
        builtinPlugin,
        runtimePlugin,
        mcpPlugin,
        githubPlugin,
        lspPlugin,
        devicePlugin,
        visionPlugin,
        automationPlugin,
        webhookPlugin,
    )

    suspend fun installStartup() = registry.installAll(startupPlugins)

    fun installedPluginIds(): List<String> = registry.ids()

    fun lifecycleSnapshots(): List<PluginLifecycleSnapshot> = registry.lifecycleSnapshots()

    suspend fun validateGitHubCredential(token: String): GitHubConnectorStatus =
        githubPlugin.validateCredential(token)

    suspend fun mcpServers(): List<McpServerSnapshot> = mcpPlugin.serverSnapshots()

    suspend fun connectMcpHttp(serverId: String, endpoint: String): String =
        mcpPlugin.connectHttpFromUi(registry.context, serverId, endpoint)

    suspend fun connectMcpStdio(
        serverId: String,
        command: List<String>,
        workingDirectory: String?,
    ): String = mcpPlugin.connectStdioFromUi(
        registry.context,
        serverId,
        command,
        workingDirectory,
    )

    suspend fun disconnectMcp(serverId: String): String =
        mcpPlugin.disconnectFromUi(registry.context, serverId)
}
