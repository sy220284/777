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
import com.labteto.dshmobile.local.LocalWebProvider
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.model.LocalApiKeyStore
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.vision.LocalVisionPlugin
import com.labteto.dshmobile.local.vision.LocalVisionRoute
import com.labteto.dshmobile.local.vision.VisionClient
import com.labteto.dshmobile.local.web.LocalWebTools
import com.labteto.dshmobile.local.files.LocalWorkspace
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
 * Platform-specific construction and plugin lifecycle stay here so LocalRuntimeKernel stays limited to
 * process lifecycle, bootstrap and recovery coordination. Downstream agent code only receives capability contracts.
 */
@Singleton
class LocalPluginCompositionFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiKeys: LocalApiKeyStore,
    private val modelGateway: LocalModelGateway,
    private val visionClient: VisionClient,
    private val githubCredentials: LocalGitHubCredentialStore,
    private val http: OkHttpClient,
    private val web: LocalWebProvider,
    private val json: Json,
    private val automationScheduler: HarnessAutomationScheduler,
    private val automationStore: AutomationStore,
    private val webhookController: WebhookController,
) {
    internal fun createWebTools(
        searchKeyProvider: suspend () -> String?,
        workspace: LocalWorkspace,
    ): LocalWebTools = LocalWebTools(web, searchKeyProvider, workspace, json)

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
        modelGateway = modelGateway,
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
    modelGateway: LocalModelGateway,
    visionClient: VisionClient,
    private val githubCredentials: LocalGitHubCredentialStore,
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
        // Production always resolves credentials from the exact route profile below.
        // Keep the legacy provider disabled so no tool can fall back to mutable global API-key state.
        keyProvider = { null },
        routeProvider = routeProvider,
        routeKeyProvider = { route ->
            val profile = route.profile
            when {
                profile == null || !modelGateway.hasCredential(profile) -> null
                profile.authKind == com.labteto.dshmobile.local.model.LocalModelAuthKind.API_KEY -> apiKeys.getFor(profile.id)
                else -> "chatgpt-plan"
            }
        },
        analyzer = visionClient,
        workspaceRoot = workspaceRoot,
        imageSupportProvider = imageSupportProvider,
        usageContextProvider = usageContextProvider,
    )
    private val automationPlugin = AutomationPlugin(automationScheduler, automationStore)
    private val webhookPlugin = WebhookPlugin(webhookController)
    private val builtinPlugin = LocalBuiltinPlugin(executeBuiltin)

    val virtualDisplayProvider: HarnessVirtualDisplayProvider
        get() = registry.context.capabilities.require("android-device", HarnessVirtualDisplayProvider::class)

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

    suspend fun disablePlugin(id: String): Boolean {
        requireReloadablePlugin(id)
        return pluginManager.disable(id)
    }

    suspend fun replacePlugin(definition: PluginDefinition) {
        // These providers are shared with long-lived subagent/runtime owners outside the tool view.
        requireReloadablePlugin(definition.descriptor.id)
        pluginManager.replace(definition)
    }

    private fun requireReloadablePlugin(id: String) {
        require(id !in setOf("android-runtime", "android-device")) {
            "平台资源仍被运行任务持有，请重启运行环境后更换或停用"
        }
    }

    fun installedPluginIds(): List<String> = pluginManager.installedPluginIds()

    fun pluginDescriptors(): List<PluginDescriptor> = pluginManager.descriptors()

    fun lifecycleSnapshots(): List<PluginLifecycleSnapshot> = pluginManager.lifecycleSnapshots()

    suspend fun githubConfigured(): Boolean = githubCredentials.configured()

    suspend fun configureGitHubCredential(token: String): GitHubConnectorStatus =
        validateGitHubCredential(token).also { githubCredentials.put(token) }

    suspend fun clearGitHubCredential() = githubCredentials.clear()

    suspend fun validateGitHubCredential(token: String): GitHubConnectorStatus =
        pluginManager.withActivePlugin("github-connector") { plugin, _ ->
            (plugin as? GitHubConnectorPlugin ?: error("GitHub 插件管理接口不兼容")).validateCredential(token)
        }

    suspend fun mcpServers(): List<McpServerSnapshot> = withMcp { plugin, _ -> plugin.serverSnapshots() }

    suspend fun connectMcpHttp(serverId: String, endpoint: String): String = withMcp { plugin, active ->
        plugin.connectHttpFromUi(active, serverId, endpoint)
    }

    suspend fun connectMcpStdio(serverId: String, command: List<String>, workingDirectory: String?): String =
        withMcp { plugin, active -> plugin.connectStdioFromUi(active, serverId, command, workingDirectory) }

    suspend fun disconnectMcp(serverId: String): String = withMcp { plugin, active ->
        plugin.disconnectFromUi(active, serverId)
    }

    private suspend fun <T> withMcp(block: suspend (McpToolBridgePlugin, HarnessContext) -> T): T =
        pluginManager.withActivePlugin("mcp-bridge") { plugin, active ->
            block(plugin as? McpToolBridgePlugin ?: error("MCP 插件管理接口不兼容"), active)
        }

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
