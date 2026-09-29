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
import com.labteto.dshmobile.harness.plugin.HarnessPluginFactory
import com.labteto.dshmobile.harness.plugin.PluginCatalog
import com.labteto.dshmobile.harness.plugin.PluginDefinition
import com.labteto.dshmobile.harness.plugin.PluginDependency
import com.labteto.dshmobile.harness.plugin.PluginDescriptor
import com.labteto.dshmobile.harness.plugin.PluginLifecycleSnapshot
import com.labteto.dshmobile.harness.plugin.PluginManager
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
import com.labteto.dshmobile.local.TokenUsageContext
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
        usageContextProvider: (String?, String?) -> TokenUsageContext? = { _, _ -> null },
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
        usageContextProvider = usageContextProvider,
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
    usageContextProvider: (String?, String?) -> TokenUsageContext? = { _, _ -> null },
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
        usageContextProvider = usageContextProvider,
    )
    private val automationPlugin = AutomationPlugin(automationScheduler, automationStore)
    private val webhookPlugin = WebhookPlugin(webhookController)
    private val builtinPlugin = LocalBuiltinPlugin(executeBuiltin)

    val virtualDisplayProvider: HarnessVirtualDisplayProvider
        get() = deviceProvider

    private val pluginCatalog = PluginCatalog(
        listOf(
            pluginDefinition(
                builtinPlugin,
                capabilities = setOf("local.tools.builtin"),
            ),
            pluginDefinition(
                runtimePlugin,
                capabilities = setOf("runtime.process", "runtime.terminal"),
            ),
            pluginDefinition(
                mcpPlugin,
                dependencies = listOf(PluginDependency("android-runtime")),
                capabilities = setOf("interop.mcp"),
            ),
            pluginDefinition(
                githubPlugin,
                capabilities = setOf("interop.github"),
            ),
            pluginDefinition(
                lspPlugin,
                dependencies = listOf(PluginDependency("android-runtime")),
                capabilities = setOf("interop.lsp"),
            ),
            pluginDefinition(
                devicePlugin,
                capabilities = setOf("device.android"),
            ),
            pluginDefinition(
                visionPlugin,
                dependencies = listOf(PluginDependency("android-device")),
                capabilities = setOf("vision.local"),
            ),
            pluginDefinition(
                automationPlugin,
                capabilities = setOf("automation.local"),
            ),
            pluginDefinition(
                webhookPlugin,
                capabilities = setOf("automation.webhook"),
            ),
        ),
    )
    private val pluginManager = PluginManager(
        catalog = pluginCatalog,
        registry = registry,
    )

    suspend fun installStartup() = pluginManager.installAll(pluginCatalog.ids())

    suspend fun enablePlugin(id: String): List<String> = pluginManager.enable(id)

    suspend fun disablePlugin(id: String): Boolean = pluginManager.disable(id)

    suspend fun replacePlugin(definition: PluginDefinition) = pluginManager.replace(definition)

    fun installedPluginIds(): List<String> = pluginManager.installedPluginIds()

    fun pluginDescriptors(): List<PluginDescriptor> = pluginManager.descriptors()

    fun lifecycleSnapshots(): List<PluginLifecycleSnapshot> = pluginManager.lifecycleSnapshots()

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

private fun pluginDefinition(
    plugin: HarnessPlugin,
    version: Int = 1,
    dependencies: List<PluginDependency> = emptyList(),
    capabilities: Set<String> = emptySet(),
): PluginDefinition = PluginDefinition(
    descriptor = PluginDescriptor(
        id = plugin.id,
        version = version,
        dependencies = dependencies,
        capabilities = capabilities,
        entryPoint = plugin::class.java.name,
    ),
    factory = HarnessPluginFactory { plugin },
)
