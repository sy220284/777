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
import kotlinx.serialization.json.jsonPrimitive
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
    private val workspaceGate: com.labteto.dshmobile.local.tools.LocalWorkspaceReadWriteGate,
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
    internal val agentWorkspaces = com.labteto.dshmobile.runtime.GitWorkspaceRuntime(File(workspace.path), process)

    internal suspend fun resolveAgentWorkspace(sessionId: String, identity: String): com.labteto.dshmobile.local.files.LocalWorkspaceExecutionContext {
        val admission = agentWorkspaces.operate(sessionId, "locate", buildJsonObject { put("workspace_id", identity) })
        return com.labteto.dshmobile.local.files.LocalWorkspaceExecutionContext(
            File(workspace.path), workspace.atRoot(File(admission.getValue("root").jsonPrimitive.content)), identity,
            File(admission.getValue("source").jsonPrimitive.content),
        )
    }

    internal suspend fun forkAgentWorkspace(sessionId: String, invocationId: String, repository: String): kotlinx.serialization.json.JsonObject {
        val tool = requireNotNull(registry.get("workspace_fork")) { "工作区运行时未启用" }
        val lease = workspaceGate.acquire(tool)
        try {
            check(registry.get("workspace_fork") === tool) { "工作区运行时已更新，请重试" }
            kotlinx.coroutines.currentCoroutineContext()[com.labteto.dshmobile.harness.agent.AgentRunExecutionContext]?.requireCurrent(sessionId)
            return agentWorkspaces.fork(sessionId, invocationId, repository)
        } finally {
            lease?.close()
        }
    }

    internal suspend fun integrateAgentWorkspace(
        sessionId: String, identity: String, tree: String, allowedPaths: List<String>, beforeCommit: suspend () -> Unit,
    ): kotlinx.serialization.json.JsonObject {
        val tool = requireNotNull(registry.get("workspace_merge")) { "工作区运行时未启用" }
        val lease = workspaceGate.acquire(tool)
        try {
            check(registry.get("workspace_merge") === tool) { "工作区运行时已更新" }
            beforeCommit()
            return agentWorkspaces.operate(sessionId, "merge", buildJsonObject {
                put("workspace_id", identity); put("expected_tree", tree)
                put("allowed_paths", kotlinx.serialization.json.JsonArray(allowedPaths.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            })
        } finally { lease?.close() }
    }
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
            workspaceReadWriteGate = workspaceGate,
            currentSessionId = runtimeStateStore::currentSessionId,
            planMode = { runtimeStateStore.state.value.work.planMode },
            networkSearchEnabled = networkSearchSettings::isEnabled,
            pinnedConnectorIds = {
                pinnedInvocations.current()
                    .filter { it.kind == com.labteto.dshmobile.local.tools.LocalPinnedInvocation.Kind.CONNECTOR }
                    .map { it.id }
            },
            pinnedSkillIds = {
                pinnedInvocations.current()
                    .filter { it.kind == com.labteto.dshmobile.local.tools.LocalPinnedInvocation.Kind.SKILL }
                    .map { it.id }
            },
            requestApproval = { call, tool, summary ->
                approvalRuntime.approve(call, tool, summary)
            },
            recordAuthorizationDecision = { sessionId, call, identity, approved ->
                sessionStorage.eventLogs.get(sessionId).append(
                    "tool/authorization-decision",
                    buildJsonObject {
                        put("session_id", sessionId)
                        put("id", call.id)
                        put("name", call.name)
                        put("execution_id", identity.executionId)
                        put("root_call_id", identity.rootCallId)
                        put("approved", approved)
                        identity.attributes["run_id"]?.let { put("run_id", it) }
                    },
                )
            },
            recordExecutionStarted = { sessionId, call, identity ->
                sessionStorage.eventLogs.get(sessionId).append(
                    "tool/execution-started",
                    buildJsonObject {
                        put("id", call.id)
                        put("name", call.name)
                        put("execution_id", identity.executionId)
                        put("read_only", identity.verifiedReadOnly)
                        put("attributes", kotlinx.serialization.json.JsonObject(identity.attributes.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
                        put("root_call_id", identity.rootCallId)
                        identity.parentExecutionId?.let { put("parent_execution_id", it) }
                    },
                )
            },
            recordCancelledExecutionFinished = { sessionId, call, identity, result ->
                // The original executor has actually returned. Record under its exact execution
                // ID before cancellation unwinds; this cannot mark the Turn/Goal completed.
                sessionStorage.eventLogs.get(sessionId).append("tool/result", buildJsonObject {
                    put("id", call.id)
                    put("name", call.name)
                    put("execution_id", identity.executionId)
                    put("is_error", result.isError)
                    put("content", result.content)
                    put("retryable", false)
                    put("side_effect", if (identity.verifiedReadOnly) "none" else "possible")
                    result.errorCode?.let { put("error_code", it) }
                    result.processExitCode?.let { put("process_exit_code", it) }
                    put("settlement_origin", "executor_after_cancellation")
                    identity.attributes["run_id"]?.let { put("run_id", it) }
                })
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
    override fun serverChanges(): kotlinx.coroutines.flow.Flow<Unit> =
        kotlinx.coroutines.flow.combine(plugins.mcpLifecycleRevisions(), pinnedInvocations.selected) { _, _ -> Unit }
    override suspend fun lifecycleHistory(
        incarnation: String?, afterSequence: Long,
    ) = plugins.mcpLifecycleHistory(incarnation, afterSequence)
    override suspend fun checkMcpHealth(serverId: String) = plugins.checkMcpHealth(serverId)
    override fun selectedConnectorIds(): Set<String> =
        pinnedInvocations.current().asSequence()
            .filter { it.kind == com.labteto.dshmobile.local.tools.LocalPinnedInvocation.Kind.CONNECTOR }
            .map { it.id }.toSet()

    override fun pluginLifecycleSnapshots() = plugins.lifecycleSnapshots()
    override suspend fun recoverFailedPlugin(id: String) = plugins.recoverFailedPlugin(id)
    override fun installedPluginIds(): List<String> = plugins.installedPluginIds()
    override fun capabilityCatalogue(): List<com.labteto.dshmobile.local.tools.LocalToolCatalogueEntry> =
        registry.names().sorted().mapNotNull { name ->
            registry.get(name)?.takeUnless {
                it.exposure == com.labteto.dshmobile.harness.tools.ToolExposure.INTERNAL
            }?.let { tool ->
                com.labteto.dshmobile.local.tools.LocalToolCatalogueEntry(
                    tool.name, tool.metadata.family, tool.access.name, tool.approvalPolicy.name,
                    tool.metadata.requirements,
                    (tool.schema["function"] as kotlinx.serialization.json.JsonObject)["parameters"]
                        as kotlinx.serialization.json.JsonObject,
                )
            }
        }

    override fun validateCapabilityDraft(
        name: String, expectedSchema: kotlinx.serialization.json.JsonObject, argumentsJson: String,
    ): com.labteto.dshmobile.uicontract.UiToolDraftValidation =
        com.labteto.dshmobile.local.tools.validateCapabilityDraftAgainstRegistry(
            registry, name, expectedSchema, argumentsJson,
        )

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
