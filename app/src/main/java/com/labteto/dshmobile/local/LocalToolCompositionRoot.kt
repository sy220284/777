package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import android.content.Context
import com.labteto.dshmobile.local.lsp.parseLanguageServerCommand
import com.labteto.dshmobile.local.model.LocalImageCapability
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.runtime.LocalBundledRuntimeManager
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.tools.LocalPluginComposition
import com.labteto.dshmobile.local.tools.LocalPluginCompositionFactory
import com.labteto.dshmobile.local.tools.LocalToolActivityProjectionRuntime
import com.labteto.dshmobile.local.tools.LocalToolSchemaProjection
import com.labteto.dshmobile.local.tools.LocalNetworkSearchSettings
import com.labteto.dshmobile.local.tools.LocalToolsManagementPort
import com.labteto.dshmobile.interop.mcp.McpServerSnapshot
import com.labteto.dshmobile.local.usage.LocalTokenUsageContextBridge
import com.labteto.dshmobile.local.usage.LocalTokenUsageSessionFacts
import com.labteto.dshmobile.local.work.LocalWorkRunRegistry
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.runtime.AndroidProcessRuntime
import com.labteto.dshmobile.runtime.PersistentPipeTerminalProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Application composition owner for the one process-wide local Tool/Plugin graph.
 *
 * This object owns platform construction only: one workspace, ToolRegistry, PluginRegistry,
 * process/terminal providers and schema/execution surfaces. Product semantics stay in Feature
 * handlers. Built-in execution is resolved lazily to break the Tool↔Feature composition cycle
 * without creating a second registry or routing execution through the Runtime Kernel.
 */
@Singleton
internal class LocalToolCompositionRoot @Inject constructor(
    @ApplicationContext context: Context,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    bundledRuntimeManager: LocalBundledRuntimeManager,
    modelConfiguration: LocalModelConfigurationCoordinator,
    pluginFactory: LocalPluginCompositionFactory,
    workRunRegistry: LocalWorkRunRegistry,
    builtinRuntime: Provider<LocalBuiltinToolRuntime>,
    private val approvalRuntime: LocalToolApprovalRuntime,
    private val activityProjection: LocalToolActivityProjectionRuntime,
    private val networkSearchSettings: LocalNetworkSearchSettings,
    private val skillManager: com.labteto.dshmobile.local.tools.LocalSkillManager,
    private val pinnedInvocations: com.labteto.dshmobile.local.tools.LocalPinnedInvocationStore,
) : LocalToolsManagementPort {
    internal val workspace = sessionStorage.files.workspace
    internal val toolOutputStore = LocalToolOutputStore(
        File(context.noBackupFilesDir, "local-harness/tool-output"),
    )
    internal val process = AndroidProcessRuntime(
        defaultWorkingDirectory = File(workspace.path),
        dynamicSearchPaths = bundledRuntimeManager::searchPaths,
        baseEnvironment = bundledRuntimeManager::environment,
    )
    private val preferences = LocalHarnessPreferences.from(context)
    private val languageServerComponents = OnDemandLanguageServerManager(
        home = File(context.noBackupFilesDir, "local-harness/lsp-components"),
        commandAvailable = process::isCommandAvailable,
        resolveCommand = process::resolveCommand,
        processEnvironment = { process.processEnvironment() },
    )
    private val languageServerResolver = AutomaticLanguageServerResolver(
        root = File(workspace.path),
        commandAvailable = process::isCommandAvailable,
        installedCommand = languageServerComponents::installedCommand,
        legacyCommand = {
            parseLanguageServerCommand(
                preferences.getString("language_server_command", "").orEmpty(),
            )
        },
    )
    private val terminal = PersistentPipeTerminalProvider(
        defaultWorkingDirectory = File(workspace.path),
        extraSearchPaths = bundledRuntimeManager::searchPaths,
        baseEnvironment = bundledRuntimeManager::environment,
    )
    internal val webTools = pluginFactory.createWebTools(
        searchKeyProvider = modelConfiguration::resolveDeepSeekSearchCredential,
        workspace = workspace,
    )
    private val usageBridge = LocalTokenUsageContextBridge(
        sessionFacts = { sessionId ->
            val summary = sessionStorage.coordinator.summaries().firstOrNull { it.id == sessionId }
            LocalTokenUsageSessionFacts(
                mode = summary?.usageMode,
                title = summary?.title,
            )
        },
        eventLogFor = sessionStorage.eventLogs::get,
        currentSessionId = runtimeStateStore::currentSessionId,
    )

    internal val plugins: LocalPluginComposition by lazy {
        pluginFactory.create(
            workspaceRoot = File(workspace.path),
            runtimeProcess = process,
            runtimeTerminal = terminal,
            languageServerCommand = languageServerResolver::resolve,
            canInstallLanguageServer = { path ->
                languageServerResolver.managedHint(path)
                    ?.let { languageServerComponents.componentFor(it) } != null
            },
            installLanguageServer = { path ->
                languageServerComponents.ensureInstalled(languageServerResolver.managedHint(path))
            },
            languageServerStatus = {
                languageServerComponents.status("probe.ts") + "；" +
                    languageServerComponents.status("probe.py")
            },
            uninstallLanguageServer = { id ->
                OnDemandLanguageServerManager.Component.entries
                    .firstOrNull { it.id == id }
                    ?.let(languageServerComponents::uninstall) ?: false
            },
            resourceScheduler = runtimeStateStore.resourceScheduler,
            routeProvider = {
                val current = runtimeStateStore.state.value
                current.modelState.modelSelection.activeProfile
                    ?.takeIf { current.modelState.configured }
                    ?.let { com.labteto.dshmobile.local.vision.LocalVisionRoute(it.baseUrl, it.model, profile = it) }
            },
            imageSupportProvider = { route ->
                when (runtimeStateStore.imageCapabilities.state(route.baseUrl, route.model)) {
                    LocalImageCapability.SUPPORTED -> true
                    LocalImageCapability.UNSUPPORTED -> false
                    LocalImageCapability.UNKNOWN -> null
                }
            },
            executeBuiltin = { call: LocalToolCall, allowMutation: Boolean, sessionId: String? ->
                builtinRuntime.get().executeResult(call, allowMutation, sessionId)
            },
            usageContextProvider = { sessionId, callId ->
                usageBridge.resolve(sessionId, callId, TokenUsageAction.VISION)
            },
        )
    }

    internal val registry get() = plugins.tools

    internal val execution: LocalToolExecutionCoordinator by lazy {
        LocalToolExecutionCoordinator(
            registry = registry,
            currentSessionId = runtimeStateStore::currentSessionId,
            planMode = { runtimeStateStore.state.value.work.planMode },
            networkSearchEnabled = networkSearchSettings::isEnabled,
            pinnedConnectorIds = {
                pinnedInvocations.selected.value
                    .filter { it.kind == com.labteto.dshmobile.local.tools.LocalPinnedInvocation.Kind.CONNECTOR }
                    .map { it.id }
            },
            pinnedSkillIds = {
                pinnedInvocations.selected.value
                    .filter { it.kind == com.labteto.dshmobile.local.tools.LocalPinnedInvocation.Kind.SKILL }
                    .map { it.id }
            },
            requestApproval = { call, tool, summary ->
                approvalRuntime.approve(call, tool, summary)
            },
            recordExecutionStarted = { sessionId, call, identity ->
                sessionStorage.eventLogs.get(sessionId).append(
                    "tool/execution-started",
                    buildJsonObject {
                        put("id", call.id)
                        put("name", call.name)
                        put("execution_id", identity.executionId)
                        put("attributes", kotlinx.serialization.json.JsonObject(identity.attributes.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
                        put("root_call_id", identity.rootCallId)
                        identity.parentExecutionId?.let { put("parent_execution_id", it) }
                    },
                )
            },
        )
    }

    internal val schemas: LocalToolSchemaProjection by lazy {
        LocalToolSchemaProjection(registry, execution)
    }

    internal fun activitySnapshot(sessionId: String) =
        activityProjection.snapshot(sessionStorage.eventLogs.get(sessionId))

    internal suspend fun installStartup() = plugins.installStartup()

    override suspend fun servers(): List<McpServerSnapshot> = plugins.mcpServers()

    override fun installedPluginIds(): List<String> = plugins.installedPluginIds()
    override fun capabilityCatalogue(): List<com.labteto.dshmobile.local.tools.LocalToolCatalogueEntry> =
        registry.names().sorted().mapNotNull { name ->
            registry.get(name)?.takeUnless {
                it.exposure == com.labteto.dshmobile.harness.tools.ToolExposure.INTERNAL
            }?.let { tool ->
                com.labteto.dshmobile.local.tools.LocalToolCatalogueEntry(
                    tool.name, tool.metadata.family, tool.access.name, tool.approvalPolicy.name,
                    tool.metadata.requirements,
                )
            }
        }

    override fun installedSkills() = skillManager.installed()
    override fun presetSkills() = skillManager.presets()
    override fun installPreset(id: String) = skillManager.install(id)
    override fun createSkill(id: String, displayName: String, description: String, instructions: String) =
        skillManager.create(id, displayName, description, instructions)
    override fun importSkill(filename: String, bytes: ByteArray): String = skillManager.importSkill(filename, bytes)
    override fun removeSkill(id: String) = skillManager.remove(id)
    override fun readSkillDocument(id: String) = skillManager.readDocument(id)
    override fun updateSkillDocument(id: String, document: String) = skillManager.updateDocument(id, document)
    override fun setSkillModelInvocable(id: String, enabled: Boolean) = skillManager.setModelInvocable(id, enabled)

    override suspend fun connectHttp(serverId: String, endpoint: String): String =
        plugins.connectMcpHttp(serverId, endpoint)

    override suspend fun connectStdio(
        serverId: String,
        command: List<String>,
        workingDirectory: String?,
    ): String = plugins.connectMcpStdio(serverId, command, workingDirectory)

    override suspend fun disconnect(serverId: String): String = plugins.disconnectMcp(serverId)
}
