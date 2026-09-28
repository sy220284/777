package com.labteto.dshmobile.local

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.observability.DiagnosticReport
import com.labteto.dshmobile.automation.AutomationPlugin
import com.labteto.dshmobile.automation.AutomationStore
import com.labteto.dshmobile.automation.HarnessAutomationScheduler
import com.labteto.dshmobile.automation.WebhookController
import com.labteto.dshmobile.automation.WebhookPlugin
import com.labteto.dshmobile.device.AndroidDevicePlugin
import com.labteto.dshmobile.device.AndroidDeviceProvider
import com.labteto.dshmobile.harness.agent.AgentEvent
import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.harness.agent.AgentEventSink
import com.labteto.dshmobile.harness.agent.AgentLoop
import com.labteto.dshmobile.harness.agent.AgentModel
import com.labteto.dshmobile.harness.agent.AgentModelReply
import com.labteto.dshmobile.harness.agent.AgentRequestEvent
import com.labteto.dshmobile.harness.agent.AgentRequestEventSink
import com.labteto.dshmobile.harness.agent.AgentRequestExecutor
import com.labteto.dshmobile.harness.agent.AgentToolBatchExecutor
import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.agent.AgentToolExecutor
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.agent.modelVisibleContent
import com.labteto.dshmobile.harness.capability.ProcessRequest
import com.labteto.dshmobile.harness.jobs.JobSnapshot
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.resource.HarnessResourceBudget
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.harness.session.ConversationHandoffBuilder
import com.labteto.dshmobile.harness.session.FutureSessionVersionException
import com.labteto.dshmobile.harness.session.HandoffGoal
import com.labteto.dshmobile.harness.session.HandoffMessage
import com.labteto.dshmobile.harness.session.HandoffState
import com.labteto.dshmobile.harness.session.HandoffTodo
import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.harness.session.SessionRecovery
import com.labteto.dshmobile.harness.session.VersionedSessionStore
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.interop.github.GitHubConnectorPlugin
import com.labteto.dshmobile.interop.github.GitHubConnectorStatus
import com.labteto.dshmobile.local.tools.LocalGitHubCredentialStore
import com.labteto.dshmobile.interop.mcp.McpServerSnapshot
import com.labteto.dshmobile.interop.mcp.McpToolBridgePlugin
import com.labteto.dshmobile.local.context.ContextComposer
import com.labteto.dshmobile.local.context.ContextRequest
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatPendingTurn
import com.labteto.dshmobile.local.chat.ChatInteractionPlanner
import com.labteto.dshmobile.local.chat.ChatSceneState
import com.labteto.dshmobile.local.chat.ChatContinuityState
import com.labteto.dshmobile.local.chat.applySceneTurn
import com.labteto.dshmobile.local.chat.canonicalFactLines
import com.labteto.dshmobile.local.chat.commitProcessed
import com.labteto.dshmobile.local.chat.enqueuePending
import com.labteto.dshmobile.local.chat.rebaseGeneration
import com.labteto.dshmobile.local.chat.restoreBranchContext
import com.labteto.dshmobile.local.chat.withContextForPlanner
import com.labteto.dshmobile.local.chat.withLegacyFallback
import com.labteto.dshmobile.local.chat.evaluateChatProactivePolicy
import com.labteto.dshmobile.local.chat.evaluateChatSilenceTrigger
import com.labteto.dshmobile.local.chat.isNearDuplicateProactive
import com.labteto.dshmobile.local.chat.nextQuietHoursEndMillis
import com.labteto.dshmobile.local.chat.proactiveConversationFocus
import com.labteto.dshmobile.local.chat.recentProactiveAvoidanceContext
import com.labteto.dshmobile.local.chat.ChatPersonaStore
import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.ChatTurnRunner
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.profile.UserProfile
import com.labteto.dshmobile.local.profile.UserProfileStore
import com.labteto.dshmobile.runtime.AndroidProcessRuntime
import com.labteto.dshmobile.runtime.AndroidRuntimePlugin
import com.labteto.dshmobile.runtime.PersistentPipeTerminalProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * A native Android implementation of the DeepSeek Harness execution loop.
 *
 * The official Harness keeps model-visible state in a durable session log and composes capabilities
 * around an agent loop. This engine preserves those two properties while replacing Node-specific
 * providers with Android providers: an app-private filesystem, `/system/bin/sh`, OkHttp and Android
 * Keystore. Remote mode remains separate and unchanged.
 */
@Singleton
class LocalHarnessEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiKeys: LocalApiKeyStore,
    private val modelClient: DeepSeekClient,
    private val modelConnectionTester: LocalModelConnectionTester,
    private val usageTracker: DeepSeekUsageTracker,
    private val visionClient: VisionClient,
    private val githubCredentials: LocalGitHubCredentialStore,
    private val bundledNodeRuntime: BundledNodeRuntime,
    private val bundledPythonRuntime: BundledPythonRuntime,
    private val bundledGitRuntime: BundledGitRuntime,
    private val http: OkHttpClient,
    private val web: LocalWebProvider,
    private val json: Json,
    private val automationScheduler: HarnessAutomationScheduler,
    private val automationStore: AutomationStore,
    private val webhookController: WebhookController,
    private val userProfileStore: UserProfileStore,
    private val memoryStore: MemoryStore,
    private val memoryManager: MemoryManager,
    private val contextComposer: ContextComposer,
    private val chatPersonaStore: ChatPersonaStore,
    private val chatPersonaGalleryStore: ChatPersonaGalleryStore,
    private val chatTurnRunner: ChatTurnRunner,
    private val chatInteractionPlanner: ChatInteractionPlanner,
) {
    private val root = File(context.filesDir, "local-harness").apply { mkdirs() }
    private val memoryClassMb = context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 256
    private val persistentJobStore = LocalPersistentJobStore(
        file = File(root, "jobs.json"),
        json = json,
    )
    private val runtimeEnvironment = LocalBundledRuntimeEnvironment(
        node = bundledNodeRuntime,
        python = bundledPythonRuntime,
        git = bundledGitRuntime,
    )
    private val workspace = LocalWorkspace(
        root = File(root, "workspace"),
        extraSearchPaths = runtimeEnvironment::searchPaths,
        environmentProvider = runtimeEnvironment::environment,
        boundary = LocalSandboxBoundary(
            workspaceRoot = File(root, "workspace"),
            userRoots = localSharedStorageRoots(),
        ),
    )
    private val fileInspector = LocalFileInspector(File(workspace.path))
    private val attachmentImporter = LocalAttachmentImporter(
        context = context,
        workspace = workspace,
        maxAttachmentBytes = MAX_ATTACHMENT_BYTES,
    )
    private val toolOutputStore = LocalToolOutputStore(
        File(context.noBackupFilesDir, "local-harness/tool-output"),
    )
    private val webTools = LocalWebTools(web, apiKeys, workspace, json)
    private val preferences = context.getSharedPreferences("local_harness", Context.MODE_PRIVATE)
    private val modelConfiguration = LocalModelConfigurationCoordinator(
        preferences = preferences,
        apiKeys = apiKeys,
        tester = modelConnectionTester,
        json = json,
    )
    private val approvalPreferences = LocalApprovalPreferences(preferences)
    private val chatTurnCoordinator = LocalChatTurnCoordinator(
        runner = chatTurnRunner,
        interactionPlanner = chatInteractionPlanner,
    )
    private val sessionsRoot = File(root, "sessions").apply { mkdirs() }
    private val sessionStorageManager by lazy { LocalSessionStorageManager(sessionsRoot, json) }
    private val sessionRepository by lazy {
        LocalSessionRepository(sessionsRoot, json, scope,
            onWritten = { _state.update { it.copy(sessions = sessionSummaries()) } },
            onError = { error -> _state.update { it.copy(error = error.message ?: "会话写入失败") } },
        )
    }
    private val sessionCoordinator by lazy {
        LocalSessionCoordinator(
            repository = sessionRepository,
            eventLogFor = ::eventLogFor,
            runtimeWindowMessages = LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES,
        )
    }
    private val agentRunCoordinator by lazy {
        LocalAgentRunCoordinator(eventLogFor = ::eventLogFor)
    }
    private val toolRegistry = ToolRegistry()
    private val pluginRegistry = PluginRegistry(HarnessContext(tools = toolRegistry))
    private val enabledOptionalTools = linkedSetOf<String>()
    private val toolExecutionCoordinator by lazy {
        LocalToolExecutionCoordinator(
            registry = toolRegistry,
            currentSessionId = { currentSessionId },
            planMode = { _state.value.planMode },
            enabledOptionalTools = enabledOptionalTools,
            requestApproval = { call, tool, summary -> approve(call, summary, tool) },
        )
    }
    private val runtimeProcess = AndroidProcessRuntime(
        defaultWorkingDirectory = File(workspace.path),
        dynamicSearchPaths = runtimeEnvironment::searchPaths,
        baseEnvironment = runtimeEnvironment::environment,
    )
    private val automaticLanguageServerResolver = AutomaticLanguageServerResolver(
        root = File(workspace.path),
        commandAvailable = runtimeProcess::isCommandAvailable,
        legacyCommand = {
            parseLanguageServerCommand(
                preferences.getString("language_server_command", "").orEmpty(),
            )
        },
    )
    private val runtimeTerminal = PersistentPipeTerminalProvider(
        defaultWorkingDirectory = File(workspace.path),
        extraSearchPaths = runtimeEnvironment::searchPaths,
        baseEnvironment = runtimeEnvironment::environment,
    )
    private val handoffBuilder = ConversationHandoffBuilder(MAX_HANDOFF_CHARS)
    private val modelHistoryCheckpointCodec = ModelHistoryCheckpointCodec()
    private val historyCompactor = LocalHistoryCompactor()
    private val modelRequestCoordinator by lazy {
        LocalModelRequestCoordinator(
            modelClient = modelClient,
            resourceScheduler = resourceScheduler,
            historyCompactor = historyCompactor,
            toolSchemas = ::modelToolSchemas,
            defaultEventLog = { eventLog },
            resetPreview = {
                _streamingState.value = LocalHarnessStreamingState()
            },
            publishPreview = { preview ->
                _streamingState.update { it.copy(assistant = preview) }
            },
            persistOverflowCompaction = ::persistForegroundOverflowCompaction,
            maxStreamPreviewChars = MAX_STREAM_PREVIEW_CHARS,
            streamPreviewIntervalMs = STREAM_PREVIEW_INTERVAL_MS,
        )
    }
    private val runtimePlugin by lazy {
        AndroidRuntimePlugin(
            workspaceRoot = File(workspace.path),
            processRuntime = runtimeProcess,
            terminalProvider = runtimeTerminal,
            resourceScheduler = resourceScheduler,
        )
    }
    private val mcpPlugin = McpToolBridgePlugin(
        http = http,
        json = json,
        workspaceRoot = File(workspace.path),
        stdioCommandResolver = runtimeProcess::resolveCommand,
        stdioEnvironmentProvider = { runtimeProcess.processEnvironment() },
    )
    private val githubPlugin = GitHubConnectorPlugin(
        http = http,
        json = json,
        credentialProvider = githubCredentials::get,
    )
    private val lspPlugin by lazy {
        com.labteto.dshmobile.interop.lsp.LspPlugin(
            root = File(workspace.path),
            json = json,
            command = automaticLanguageServerResolver::resolve,
            commandResolver = runtimeProcess::resolveCommand,
            environment = { runtimeProcess.processEnvironment() },
            resourceScheduler = resourceScheduler,
        )
    }
    private val deviceProvider by lazy {
        AndroidDeviceProvider(context, resourceScheduler = resourceScheduler)
    }
    private val devicePlugin by lazy { AndroidDevicePlugin(deviceProvider) }
    private val visionPlugin by lazy {
        LocalVisionPlugin(
            device = deviceProvider,
            keyProvider = apiKeys::get,
            routeProvider = {
                val current = _state.value
                if (!current.configured) null
                else LocalVisionRoute(baseUrl = current.baseUrl, model = current.model)
            },
            analyzer = visionClient,
            workspaceRoot = File(workspace.path),
            imageSupportProvider = { route ->
                when (imageCapabilities.state(route.baseUrl, route.model)) {
                    LocalImageCapability.SUPPORTED -> true
                    LocalImageCapability.UNSUPPORTED -> false
                    LocalImageCapability.UNKNOWN -> null
                }
            },
        )
    }
    private val automationPlugin = AutomationPlugin(automationScheduler, automationStore)
    private val webhookPlugin = WebhookPlugin(webhookController)
    private val builtinPlugin = LocalBuiltinPlugin(::executeBuiltin)
    private var currentSessionId = preferences.getString(KEY_SESSION_ID, null)
        ?: UUID.randomUUID().toString()
    // Opening the log scans its latest segment. The startup coroutine initializes it after any
    // legacy migration, before the loading screen admits session actions.
    @Volatile private lateinit var eventLog: LocalSessionEventLog
    private val conversationFilesCoordinator by lazy {
        LocalConversationFilesCoordinator(
            workspace = workspace,
            eventLogFor = ::eventLogFor,
            currentSessionId = { currentSessionId },
            currentEventLog = { eventLog },
        )
    }
    private var transcriptProjectionCursor: Long? = null
    private val modelHistory = LocalModelHistoryBuffer()
    private var turnsSinceModelHistoryCheckpoint = 0
    private val _state = MutableStateFlow(
        LocalHarnessState(
            workspacePath = workspace.path,
            sessionId = currentSessionId,
            usage = usageTracker.state.value,
            chatStyleGuardEnabled = preferences.getBoolean(
                LocalHarnessSettingsCoordinator.KEY_CHAT_STYLE_GUARD,
                true,
            ),
            chatStyleGuardCustomPhrases =
                LocalHarnessSettingsCoordinator.loadChatStyleGuardCustomPhrases(preferences),
        ),
    )
    private val _streamingState = MutableStateFlow(LocalHarnessStreamingState())
    internal val state: StateFlow<LocalHarnessState> = _state.asStateFlow()
    internal val streamingState: StateFlow<LocalHarnessStreamingState> = _streamingState.asStateFlow()

    private val transcriptRuntime by lazy {
        LocalTranscriptRuntime(
            state = _state,
            pruneToolResult = ::pruneToolResult,
            runtimeWindowMessages = LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES,
            onProjected = { sequence ->
                transcriptProjectionCursor = maxOf(transcriptProjectionCursor ?: -1L, sequence)
            },
        )
    }

    private val chatContextRefreshCoordinator by lazy {
        LocalChatContextRefreshCoordinator(
            state = _state,
            scope = scope,
            chatTurnCoordinator = chatTurnCoordinator,
            requestPlanner = { snapshot, prompt, requestLog ->
                apiKeys.get()?.let { key ->
                    completeWithRetry(
                        key = key,
                        snapshot = snapshot,
                        messages = listOf(
                            buildJsonObject {
                                put("role", "system")
                                put("content", prompt)
                            },
                        ),
                        step = CHAT_POST_TURN_MODEL_STEP,
                        toolsOverride = JsonArray(emptyList()),
                        publishPreview = false,
                        requestLog = requestLog,
                    )
                }
            },
            recordUsage = { snapshot, reply -> usageTracker.record(snapshot.model, reply.usage) },
            persistBranchState = ::persistChatBranchState,
            persist = ::persist,
        )
    }

    private val chatReplyCoordinator by lazy {
        LocalChatReplyCoordinator(
            chatTurnCoordinator = chatTurnCoordinator,
            recordUsage = { snapshot, reply -> usageTracker.record(snapshot.model, reply.usage) },
            recordStyleGuardHits = ::recordStyleGuardHits,
        )
    }

    // SupervisorJob keeps one failed child from cancelling unrelated engine work. The handler is the
    // final visibility boundary; operation-specific busy/loading state is still owned by each launch.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                AppLog.error("LocalHarnessEngine", "engine coroutine failure", throwable)
                _state.update { current ->
                    current.copy(
                        error = throwable.message?.takeIf(String::isNotBlank)
                            ?: "本机 Harness 后台任务失败：${throwable::class.java.simpleName}",
                    )
                }
            },
    )
    private val settingsCoordinator by lazy {
        LocalHarnessSettingsCoordinator(
            preferences = preferences,
            userProfileStore = userProfileStore,
            scope = scope,
            state = { _state.value },
            updateState = { transform -> _state.update(transform) },
        )
    }

    private val resourceBudget = localResourceBudgetForMemoryClass(memoryClassMb)
    private val imageCapabilities = LocalImageCapabilityRegistry()
    private val imageRequestBudget = localImageRequestBudgetForModelConcurrency(resourceBudget.maxModelRequests)
    private val resourceScheduler = HarnessResourceScheduler(
        budget = resourceBudget,
        onChanged = { snapshot ->
            _state.update { current ->
                current.copy(
                    activeModelRequests = snapshot.activeModelRequests,
                    activeAgents = projectWorkResourceCount(current.usageMode, snapshot.activeAgents),
                    activeTerminals = projectWorkResourceCount(current.usageMode, snapshot.activeTerminals),
                    activeVirtualDisplays = projectWorkResourceCount(
                        current.usageMode,
                        snapshot.activeVirtualDisplays,
                    ),
                    activeLanguageServers = projectWorkResourceCount(
                        current.usageMode,
                        snapshot.activeLanguageServers,
                    ),
                    maxModelRequests = snapshot.budget.maxModelRequests,
                    maxAgents = snapshot.budget.maxAgents,
                    maxTerminals = snapshot.budget.maxTerminals,
                    maxVirtualDisplays = snapshot.budget.maxVirtualDisplays,
                    maxLanguageServers = snapshot.budget.maxLanguageServers,
                    resourcePressure = snapshot.pressure.name.lowercase(),
                    contextBudgetChars = localHistoryBudgetFor(memoryClassMb, snapshot.pressure).maxHistoryChars,
                )
            }
        },
    )
    private val jobs = LocalJobManager(scope, persistentJobStore) { snapshot ->
        _state.update { current ->
            current.copy(jobs = projectExecutionJobs(current.usageMode, snapshot))
        }
        syncForegroundJobs(context, currentSessionId, snapshot) { message ->
            _state.update { it.copy(error = message) }
        }
    }

    private val memoryTools = LocalMemoryTools(memoryStore, memoryManager, { _state.value }, { currentSessionId })

    private val subagentRunnerFactory by lazy {
        LocalSubagentRunnerFactory(
            apiKeys = apiKeys,
            modelClient = modelClient,
            jobs = jobs,
            modelHistory = modelHistory,
            toolOutputStore = toolOutputStore,
            workspacePath = workspace.path,
            imageRequestBudget = imageRequestBudget,
            imageCapabilities = imageCapabilities,
            usageTracker = usageTracker,
            resourceScheduler = resourceScheduler,
            deviceProvider = deviceProvider,
            memoryClassMb = memoryClassMb,
            agentRunCoordinator = agentRunCoordinator,
            contextComposer = contextComposer,
            memoryStore = memoryStore,
            memoryManager = memoryManager,
            eventLogFor = ::eventLogFor,
            defaultState = state,
            defaultSessionId = { currentSessionId },
        )
    }

    private val automationWorkCoordinator by lazy {
        LocalAutomationWorkCoordinator(
            state = state,
            sessionCoordinator = sessionCoordinator,
            eventLogFor = ::eventLogFor,
            agentRunCoordinator = agentRunCoordinator,
            runnerFactory = { sessionId, boundState, onApprovalBlocked ->
                automationSubagentRunner(
                    sessionId = sessionId,
                    boundState = boundState,
                    onApprovalBlocked = onApprovalBlocked,
                )
            },
        )
    }

    private val automationChatCoordinator by lazy {
        LocalAutomationChatCoordinator(
            state = state,
            sessionCoordinator = sessionCoordinator,
            eventLogFor = ::eventLogFor,
            chatPersonaStore = chatPersonaStore,
            chatTurnCoordinator = chatTurnCoordinator,
            chatReplyCoordinator = chatReplyCoordinator,
            usageTracker = usageTracker,
            apiKeys = apiKeys,
            modelRequestCoordinator = modelRequestCoordinator,
            chatRelationshipMemoryContext = ::chatRelationshipMemoryContext,
            recordStyleGuardHits = ::recordStyleGuardHits,
            acquireVisibleTurn = ::acquireAutomationChatVisibleTurn,
            commitVisibleReply = ::commitVisibleAutomationChatReply,
            releaseVisibleTurn = ::releaseAutomationChatVisibleTurn,
        )
    }

    private val subagents by lazy {
        subagentRunnerFactory.create(
            eventLogProvider = { eventLog },
            contextSnapshotProvider = { query ->
                val snapshot = _state.value
                contextComposer.compose(
                    ContextRequest(
                        query = query,
                        mode = snapshot.conversationMode,
                        projectId = snapshot.projectId,
                        lineageId = snapshot.lineageId,
                        handoffSummary = snapshot.handoffSummary,
                    ),
                )
            },
            schemasProvider = { allowMutation, allowVirtualScreen, enabledOptional ->
                subagentToolSchemas(allowMutation, allowVirtualScreen, enabledOptional)
            },
            executeTool = { call, allowMutation, enabledOptional ->
                val canonical = call.copy(name = LocalToolPolicy.canonical(call.name))
                if (canonical.name == "capability_search") {
                    AgentToolResult(
                        searchCapabilities(canonical.arguments.string("query"), enabledOptional),
                    )
                } else {
                    executeSafely(canonical, allowMutation)
                }
            },
        )
    }

    private fun persistentSubagentRunner(
        sessionId: String,
        boundState: LocalHarnessState,
    ): LocalSubagentRunner = subagentRunnerFactory.createBound(
        sessionId = sessionId,
        boundState = boundState,
        runKind = LocalAgentRunKind.SUBAGENT,
        schemasProvider = ::subagentToolSchemas,
        executeTool = { call, allowMutation, memoryTools, enabledOptional ->
            executePersistentSubagentTool(
                call = call,
                allowMutation = allowMutation,
                sessionId = sessionId,
                memoryTools = memoryTools,
                enabledOptionalTools = enabledOptional,
            )
        },
    )


    /**
     * Detached work runner for scheduled/webhook work.
     *
     * It owns a Work-mode session log but never swaps the UI's current session. Interactive
     * approvals are deliberately unavailable: when global auto-approval is enabled, every
     * approval-gated tool may proceed; otherwise the task is marked blocked so a background task
     * cannot surface a work prompt inside Chat mode.
     */
    private fun automationSubagentRunner(
        sessionId: String,
        boundState: LocalHarnessState,
        onApprovalBlocked: (String) -> Unit,
    ): LocalSubagentRunner = subagentRunnerFactory.createBound(
        sessionId = sessionId,
        boundState = boundState,
        runKind = LocalAgentRunKind.AUTOMATION,
        schemasProvider = ::subagentToolSchemas,
        executeTool = { call, allowMutation, memoryTools, enabledOptional ->
            executeAutomationSubagentTool(
                call = call,
                allowMutation = allowMutation,
                sessionId = sessionId,
                memoryTools = memoryTools,
                enabledOptionalTools = enabledOptional,
                onApprovalBlocked = onApprovalBlocked,
            )
        },
    )

    private val runStateLock = Any()
    private val pendingInputs = AgentInputQueue(MAX_PENDING_INPUTS)
    private val sessionTransitionMutex = Mutex()
    private var sessionTransitioning = false
    private var activeJob: Job? = null
    /** Work runs are session-owned and may outlive whichever conversation is currently visible. */
    private val activeWorkRuns = ConcurrentHashMap<String, LocalWorkRunBinding>()
    private var persistentRecoveryJob: Job? = null
    private val interactions = LocalInteractionCoordinator(_state)
    private val memoryCoordinator by lazy {
        LocalMemoryCoordinator(
            state = _state,
            memoryStore = memoryStore,
            memoryManager = memoryManager,
            currentSessionId = { currentSessionId },
            eventLog = { eventLog },
            persist = ::persist,
        )
    }
    private val approvalCoordinator by lazy {
        LocalApprovalCoordinator(
            state = _state,
            approvalPreferences = approvalPreferences,
            interactions = interactions,
            eventLog = { eventLog },
            persist = ::persist,
        )
    }
    private val sessionLifecycle by lazy {
        LocalSessionLifecycleCoordinator(
            scope = scope,
            state = _state,
            transitionMutex = sessionTransitionMutex,
            jobs = jobs,
            sessionCoordinator = sessionCoordinator,
            chatPersonaStore = chatPersonaStore,
            approvalPreferences = approvalPreferences,
            resourceScheduler = resourceScheduler,
            handoffBuilder = handoffBuilder,
            toolOutputStore = toolOutputStore,
            sessionsRoot = sessionsRoot,
            conversationFilesCoordinator = conversationFilesCoordinator,
            memoryStore = memoryStore,
            currentSessionId = { currentSessionId },
            activateSession = { id, transcriptCursor ->
                currentSessionId = id
                preferences.edit().putString(KEY_SESSION_ID, id).apply()
                eventLog = eventLogFor(id)
                transcriptProjectionCursor = transcriptCursor
            },
            beginTransition = ::beginSessionTransition,
            endTransition = ::endSessionTransition,
            runBusy = ::isRunBusy,
            cancelActiveRunAndJoin = ::cancelActiveRunAndJoin,
            resetModelHistory = { modelHistory.reset() },
            persist = ::persist,
            loadSession = { id -> loadSession(id) },
            restartInterruptedSafeJobs = ::restartInterruptedSafeJobs,
            startNextQueuedTurnIfIdle = ::startNextQueuedTurnIfIdle,
            sessionSummaries = ::sessionSummaries,
            localProjectId = LOCAL_PROJECT_ID,
        )
    }

    init {
        preferences.edit().putString(KEY_SESSION_ID, currentSessionId).apply()
        val initialResources = resourceScheduler.snapshot()
        _state.update {
            it.copy(
                maxModelRequests = initialResources.budget.maxModelRequests,
                maxAgents = initialResources.budget.maxAgents,
                maxTerminals = initialResources.budget.maxTerminals,
                maxVirtualDisplays = initialResources.budget.maxVirtualDisplays,
                maxLanguageServers = initialResources.budget.maxLanguageServers,
                resourcePressure = initialResources.pressure.name.lowercase(),
                contextBudgetChars = localHistoryBudgetFor(memoryClassMb, initialResources.pressure).maxHistoryChars,
            )
        }
        scope.launch {
            usageTracker.state.collect { usage ->
                _state.update { it.copy(usage = usage) }
            }
        }
        scope.launch {
            runCatching {
                seedLocalWorkspaceGuide(workspace.path)
                migrateLegacySessionFiles(root, sessionsRoot, currentSessionId)
                // Migration may have copied an event log after the field was first constructed.
                // Reopen it before any session load or tool can append to the migrated log.
                eventLog = eventLogFor(currentSessionId)
                bundledNodeRuntime.prepare()
                bundledPythonRuntime.prepare()
                bundledGitRuntime.prepare()
                pluginRegistry.install(builtinPlugin)
                pluginRegistry.install(runtimePlugin)
                pluginRegistry.install(mcpPlugin)
                pluginRegistry.install(githubPlugin)
                pluginRegistry.install(lspPlugin)
                pluginRegistry.install(devicePlugin)
                pluginRegistry.install(visionPlugin)
                pluginRegistry.install(automationPlugin)
                pluginRegistry.install(webhookPlugin)
                load()
                startNextQueuedTurnIfIdle()?.start()
                scheduleInterruptedSafeJobs()
                scope.launch { maybeCleanupUnreferencedLocalImages() }
                scope.launch { LocalSessionArchiveMaintenance(sessionsRoot, json, { currentSessionId }).run() }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = "本机 Harness 初始化失败：${error.message ?: error::class.java.simpleName}",
                    )
                }
            }
        }
    }

    /** Read-only file views used by the local Harness UI. Heavy filesystem work stays off main. */
    internal suspend fun workspaceFilesForUi(): List<LocalWorkspaceFile> = withContext(Dispatchers.IO) {
        conversationFilesCoordinator.workspaceFiles()
    }

    internal suspend fun conversationFilesForUi(sessionId: String = currentSessionId): LocalConversationFiles =
        withContext(Dispatchers.IO) {
            conversationFilesCoordinator.conversationFiles(sessionId)
        }

    internal suspend fun previewWorkspaceFileForUi(path: String): LocalWorkspaceFilePreview =
        withContext(Dispatchers.IO) {
            conversationFilesCoordinator.preview(path)
        }

    /** Save the local model route and its encrypted credential. */
    internal fun configure(apiKey: String, model: String, baseUrl: String) {
        scope.launch {
            runCatching { saveModelConfiguration(apiKey, model, baseUrl) }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    internal suspend fun saveModelConfiguration(apiKey: String, model: String, baseUrl: String) {
        require(!isRunBusy()) { "请先结束当前任务再切换模型" }
        val result = modelConfiguration.save(apiKey, model, baseUrl)
        _state.update {
            it.copy(
                configured = result.configured,
                model = result.model,
                baseUrl = result.baseUrl,
                configuredModels = result.configuredModels,
                modelProfiles = result.profiles,
                error = null,
            )
        }
    }

    /** Switch the active route and its corresponding encrypted key together. */
    internal fun selectModel(id: String) {
        scope.launch {
            val current = _state.value
            val selected = current.modelProfiles.firstOrNull { it.id == id } ?: return@launch
            if (
                current.loading || current.running || isRunBusy() ||
                (selected.model == current.model && selected.baseUrl == current.baseUrl)
            ) return@launch
            runCatching { modelConfiguration.select(id, current.modelProfiles) }
                .onSuccess { result ->
                    if (result != null) {
                        _state.update { state ->
                            state.copy(
                                configured = result.configured,
                                model = result.model,
                                baseUrl = result.baseUrl,
                                modelProfiles = result.profiles,
                                configuredModels = result.configuredModels,
                                error = null,
                            )
                        }
                    }
                }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    internal fun removeModelProfile(id: String) {
        if (isRunBusy() || _state.value.loading) return
        scope.launch {
            val current = _state.value
            runCatching {
                modelConfiguration.remove(id, current.model, current.baseUrl)
            }.onSuccess { result ->
                if (result != null) {
                    _state.update {
                        it.copy(
                            configured = result.configured,
                            model = result.model,
                            baseUrl = result.baseUrl,
                            modelProfiles = result.profiles,
                            configuredModels = result.configuredModels,
                        )
                    }
                }
            }.onFailure { error ->
                _state.update { it.copy(error = error.message) }
            }
        }
    }

    internal suspend fun testModelConfiguration(apiKey: String, model: String, baseUrl: String): String =
        modelConfiguration.test(apiKey, model, baseUrl)

    /** Choose how user image attachments reach the local model. */
    internal fun configureImageInputMode(mode: LocalImageInputMode) =
        settingsCoordinator.configureImageInputMode(mode)

    /** Persist execution limits exposed from Settings. */
    internal fun configureRuntimeLimits(mainMaxSteps: Int, subagentMaxSteps: Int, modelAttempts: Int) =
        settingsCoordinator.configureRuntimeLimits(mainMaxSteps, subagentMaxSteps, modelAttempts)

    /** Persist user-authored behavioral rules and memory recall preference. */
    internal fun configurePersonalization(customRules: String, autoRecall: Boolean, autoMemory: Boolean) =
        settingsCoordinator.configurePersonalization(customRules, autoRecall, autoMemory)

    /** Master switch for local chat output filtering. */
    internal fun configureChatStyleGuard(enabled: Boolean) =
        settingsCoordinator.configureChatStyleGuard(enabled)

    internal fun addChatStyleGuardPhrase(value: String): Boolean =
        settingsCoordinator.addChatStyleGuardPhrase(value)

    internal fun removeChatStyleGuardPhrase(value: String) =
        settingsCoordinator.removeChatStyleGuardPhrase(value)

    internal fun clearChatStyleGuardHits() =
        settingsCoordinator.clearChatStyleGuardHits()

    private fun chatStreamFilterPhrases(
        snapshot: LocalHarnessState,
        persona: PersonaProfile = snapshot.chatPersona,
    ): List<String> = settingsCoordinator.chatStreamFilterPhrases(snapshot, persona)

    private fun recordStyleGuardHits(violations: List<String>) =
        settingsCoordinator.recordStyleGuardHits(violations)

    internal fun configureChatPersona(profile: PersonaProfile) {
        val snapshot = _state.value
        if (
            snapshot.running ||
            snapshot.loading ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.groupChat.enabled
        ) return
        scope.launch {
            val personaId = snapshot.personaId.takeUnless {
                it == PersonaProfile.DEFAULT_PERSONA_ID
            } ?: "persona-${UUID.randomUUID()}"
            val saved = chatPersonaStore.upsert(profile.copy(id = personaId))
            val sameBoundCharacter =
                com.labteto.dshmobile.local.chat.samePersonaIdentity(snapshot.chatPersona, saved)
            _state.update { state ->
                if (state.sessionId != snapshot.sessionId) state else state.copy(
                    personaId = saved.id,
                    galleryId = state.galleryId.takeIf { sameBoundCharacter },
                    galleryStoryId = state.galleryStoryId.takeIf { sameBoundCharacter },
                    gallerySaveSuppressedThrough = if (sameBoundCharacter) {
                        state.gallerySaveSuppressedThrough
                    } else {
                        state.transcriptIndex.latestCreatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                    },
                    chatPersona = saved,
                    chatState = if (sameBoundCharacter) state.chatState else ChatCharacterState(),
                    replySuggestions = if (sameBoundCharacter) state.replySuggestions else emptyList(),
                    handoffSummary = if (sameBoundCharacter) state.handoffSummary else null,
                )
            }
            if (_state.value.sessionId == snapshot.sessionId) persist()
        }
    }

    /**
     * Pick a saved character for the current empty chat without creating a throwaway session.
     * Existing transcripts are deliberately left untouched by refusing the switch once dialogue
     * exists; callers can start a fresh chat instead.
     */
    internal fun selectChatPersona(profile: PersonaProfile, galleryId: String? = null) {
        val snapshot = _state.value
        if (
            snapshot.running ||
            snapshot.loading ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.groupChat.enabled ||
            snapshot.transcriptIndex.hasDialogue
        ) return

        scope.launch {
            val personaId = profile.id.takeUnless { it == PersonaProfile.DEFAULT_PERSONA_ID }
                ?: "persona-${UUID.randomUUID()}"
            val saved = chatPersonaStore.upsert(profile.copy(id = personaId))
            _state.update { state ->
                if (state.sessionId != snapshot.sessionId) state else state.copy(
                    personaId = saved.id,
                    galleryId = galleryId,
                    galleryStoryId = null,
                    gallerySaveSuppressedThrough = 0L,
                    chatPersona = saved,
                    chatState = ChatCharacterState(),
                    replySuggestions = emptyList(),
                    chatBranches = LocalChatBranchState(),
                    handoffSummary = null,
                )
            }
            if (_state.value.sessionId == snapshot.sessionId) persist()
        }
    }

    /** Create a brand-new character for the current empty chat. */
    internal fun createChatPersona(profile: PersonaProfile) {
        val snapshot = _state.value
        if (
            snapshot.running ||
            snapshot.loading ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.groupChat.enabled ||
            snapshot.transcriptIndex.hasDialogue
        ) return

        scope.launch {
            val saved = chatPersonaStore.upsert(
                profile.copy(id = "persona-${UUID.randomUUID()}"),
            )
            _state.update { state ->
                if (state.sessionId != snapshot.sessionId) state else state.copy(
                    personaId = saved.id,
                    galleryId = null,
                    galleryStoryId = null,
                    gallerySaveSuppressedThrough = 0L,
                    chatPersona = saved,
                    chatState = ChatCharacterState(),
                    replySuggestions = emptyList(),
                    chatBranches = LocalChatBranchState(),
                    handoffSummary = null,
                )
            }
            if (_state.value.sessionId == snapshot.sessionId) persist()
        }
    }

    internal fun bindChatGallery(galleryId: String, galleryStoryId: String?) {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.running || snapshot.usageMode != LocalUsageMode.CHAT) return
        _state.update { state ->
            if (state.sessionId != snapshot.sessionId) state else state.copy(
                galleryId = galleryId,
                galleryStoryId = galleryStoryId,
                gallerySaveSuppressedThrough = 0L,
            )
        }
        if (_state.value.sessionId == snapshot.sessionId) persist()
    }

    internal fun clearChatGalleryBinding(
        expectedGalleryId: String,
        expectedStoryId: String? = null,
        keepCharacter: Boolean = false,
    ) {
        val snapshot = _state.value
        if (snapshot.usageMode != LocalUsageMode.CHAT || snapshot.galleryId != expectedGalleryId) return
        if (expectedStoryId != null && snapshot.galleryStoryId != expectedStoryId) return
        _state.update { state ->
            if (state.sessionId != snapshot.sessionId) state else state.copy(
                galleryId = if (keepCharacter) state.galleryId else null,
                galleryStoryId = null,
                gallerySaveSuppressedThrough =
                    state.transcriptIndex.latestCreatedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        }
        if (_state.value.sessionId == snapshot.sessionId) persist()
    }

    /** A story direction is a user preference for future turns, never a synthetic user message. */
    internal fun selectChatDirection(direction: String?) {
        val snapshot = _state.value
        if (
            snapshot.loading ||
            snapshot.running ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.groupChat.enabled
        ) return
        val selected = direction?.let { requested ->
            snapshot.replySuggestions.firstOrNull { it.direction == requested }
                ?.let { com.labteto.dshmobile.local.chat.ChatNarrativeDirection(it.label, it.direction) }
                ?: return
        }
        _state.update { current ->
            if (current.sessionId != snapshot.sessionId) current else current.copy(
                chatState = current.chatState.copy(narrativeDirection = selected),
            )
        }
        eventLog.append("chat/direction", buildJsonObject {
            put("label", selected?.label.orEmpty())
            put("guidance", selected?.guidance.orEmpty())
        })
        persist()
    }

    /**
     * Persist AI-generated fields into the real default persona and make the current chat use it.
     * This is suspendable so the UI only reports "synced" after the profile file and session state
     * have both been updated.
     */
    internal suspend fun syncDefaultChatPersona(profile: PersonaProfile): PersonaProfile {
        val snapshot = _state.value
        check(
            !snapshot.running &&
                !snapshot.loading &&
                snapshot.usageMode == LocalUsageMode.CHAT &&
                !snapshot.groupChat.enabled
        ) {
            "当前状态暂时不能同步默认角色"
        }
        return withContext(Dispatchers.IO) {
            val saved = chatPersonaStore.upsert(
                profile.copy(id = PersonaProfile.DEFAULT_PERSONA_ID),
            )
            _state.update { state ->
                if (state.sessionId != snapshot.sessionId) state else state.copy(
                    personaId = PersonaProfile.DEFAULT_PERSONA_ID,
                    galleryId = null,
                    galleryStoryId = null,
                    gallerySaveSuppressedThrough =
                        state.transcriptIndex.latestCreatedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    chatPersona = saved,
                )
            }
            if (_state.value.sessionId == snapshot.sessionId) persist()
            saved
        }
    }

    internal fun createGroupChatSession() {
        createSession(
            mode = LocalConversationMode.INDEPENDENT,
            usageMode = LocalUsageMode.CHAT,
            chatMode = LocalChatMode.GROUP,
        )
    }

    internal fun createSingleChatSession() {
        createSession(
            mode = LocalConversationMode.INDEPENDENT,
            usageMode = LocalUsageMode.CHAT,
            chatMode = LocalChatMode.SINGLE,
        )
    }

    internal fun configureGroupChatMembers(entries: List<PersonaGalleryEntry>): Boolean {
        val snapshot = _state.value
        if (
            snapshot.loading ||
            snapshot.running ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            !snapshot.groupChat.enabled
        ) return false

        val selected = entries
            .distinctBy(PersonaGalleryEntry::id)
            .take(MAX_GROUP_CHAT_MEMBERS)
        if (selected.size !in MIN_GROUP_CHAT_MEMBERS..MAX_GROUP_CHAT_MEMBERS) return false
        scope.launch {
            val members = selected.map { entry ->
                val existingPersona = chatPersonaStore.get(entry.id)
                val saved = chatPersonaStore.upsert(
                    entry.persona.copy(
                        id = entry.id,
                        corrections = (entry.persona.corrections + existingPersona.corrections)
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .distinct(),
                    ),
                )
                val previous = snapshot.groupChat.members.firstOrNull { it.galleryId == entry.id }
                LocalGroupChatMember(
                    galleryId = entry.id,
                    personaId = saved.id,
                    displayName = saved.name,
                    portraitPath = entry.portraitPath,
                    persona = saved,
                    chatState = previous?.chatState
                        ?: entry.groupChatState.takeIf { it.updatedAt > 0L }
                        ?: entry.stories.maxByOrNull { it.updatedAt }?.chatState
                        ?: ChatCharacterState(),
                )
            }
            _state.update { current ->
                if (
                    current.sessionId != snapshot.sessionId ||
                    current.usageMode != LocalUsageMode.CHAT ||
                    !current.groupChat.enabled
                ) {
                    current
                } else {
                    current.copy(
                        groupChat = current.groupChat.copy(members = members),
                        replySuggestions = emptyList(),
                        chatBranches = LocalChatBranchState(),
                        error = null,
                    )
                }
            }
            if (_state.value.sessionId == snapshot.sessionId) {
                refreshGroupModelSystemPrompt()
                checkpointModelHistory("group/members-updated")
                eventLog.append("group/members", buildJsonObject {
                    put("count", members.size)
                    put("gallery_ids", JsonArray(members.map { JsonPrimitive(it.galleryId) }))
                })
                persist()
            }
        }
        return true
    }

    internal fun setGroupChatAnnouncement(text: String): Boolean {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.running || snapshot.usageMode != LocalUsageMode.CHAT ||
            !snapshot.groupChat.enabled) return false
        val announcement = text.trim().take(2_000)
        _state.update { current ->
            if (current.sessionId == snapshot.sessionId && current.groupChat.enabled) {
                current.copy(groupChat = current.groupChat.copy(announcement = announcement))
            } else current
        }
        if (_state.value.sessionId != snapshot.sessionId) return false
        persist()
        return true
    }

    internal fun removeGroupChatMemberByGalleryId(galleryId: String) {
        val snapshot = _state.value
        if (
            snapshot.loading ||
            snapshot.running ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            !snapshot.groupChat.enabled ||
            snapshot.groupChat.members.none { it.galleryId == galleryId }
        ) return

        _state.update { current ->
            if (current.sessionId != snapshot.sessionId) current else current.copy(
                groupChat = current.groupChat.copy(
                    members = current.groupChat.members.filterNot { it.galleryId == galleryId },
                    turnCursor = 0,
                ),
                groupActiveSpeakerName = null,
            )
        }
        refreshGroupModelSystemPrompt()
        checkpointModelHistory("group/member-deleted")
        eventLog.append("group/members", buildJsonObject {
            put("action", "member-deleted")
            put("gallery_id", galleryId)
            put("count", _state.value.groupChat.members.size)
        })
        persist()
    }

    /**
     * Generate reply suggestions only when the user explicitly asks for them.
     *
     * Normal chat turns never call this path, so keeping the affordance visible has zero model
     * cost until it is tapped.
     */
    internal suspend fun generateReplySuggestions(): Boolean {
        val snapshot = _state.value
        if (
            snapshot.loading ||
            !snapshot.configured ||
            snapshot.running ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.groupChat.enabled
        ) return false

        val assistantIndex = snapshot.messages.indexOfLast { message ->
            message.role == "assistant" && message.content.isNotBlank()
        }
        if (assistantIndex < 0) return false
        val assistantMessage = snapshot.messages[assistantIndex]
        val expectedSessionId = snapshot.sessionId
        val expectedAssistantMessageId = assistantMessage.id
        val boundEventLog = eventLogFor(expectedSessionId)
        val key = apiKeys.get() ?: return false
        val prompt = chatTurnCoordinator.replySuggestionsPrompt(
            persona = snapshot.chatPersona,
            state = snapshot.chatState,
            messages = snapshot.messages,
            latestAssistantMessageId = assistantMessage.id,
        )
        val reply = try {
            completeWithRetry(
                key = key,
                snapshot = snapshot,
                messages = listOf(
                    buildJsonObject {
                        put("role", "system")
                        put("content", prompt)
                    },
                ),
                step = CHAT_POST_TURN_MODEL_STEP + 1,
                toolsOverride = JsonArray(emptyList()),
                publishPreview = false,
                requestLog = boundEventLog,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            boundEventLog.append("chat/reply-suggestions", buildJsonObject {
                put("status", "failed")
                put("detail", error.message.orEmpty().take(1_000))
            })
            return false
        }
        usageTracker.record(snapshot.model, reply.usage)
        val suggestions = chatTurnCoordinator.parseReplySuggestions(reply.content.orEmpty())
        if (suggestions.isNullOrEmpty()) {
            boundEventLog.append("chat/reply-suggestions", buildJsonObject {
                put("status", "parse-failed")
                put("content", reply.content.orEmpty().take(2_000))
            })
            return false
        }

        var applied = false
        _state.update { current ->
            if (
                current.sessionId != expectedSessionId ||
                current.usageMode != LocalUsageMode.CHAT ||
                current.groupChat.enabled ||
                current.transcriptIndex.latestDialogueMessageId != expectedAssistantMessageId
            ) {
                current
            } else {
                applied = true
                current.copy(
                    replySuggestions = suggestions,
                    chatBranches = if (current.transcriptIndex.branchingEligible) {
                        updateChatBranchNodeSnapshot(
                            state = current.chatBranches,
                            messageId = expectedAssistantMessageId,
                            chatState = current.chatState,
                            chatContext = current.chatContext,
                            replySuggestions = suggestions,
                        )
                    } else {
                        current.chatBranches
                    },
                )
            }
        }
        if (!applied) {
            boundEventLog.append("chat/reply-suggestions", buildJsonObject {
                put("status", "stale-discarded")
                put("assistant_message_id", expectedAssistantMessageId)
            })
            return false
        }

        boundEventLog.append("chat/reply-suggestions", buildJsonObject {
            put("status", "updated")
            put("assistant_message_id", expectedAssistantMessageId)
            put("suggestion_count", suggestions.size)
        })
        if (hasChatBranchAlternatives(_state.value.chatBranches)) {
            persistChatBranchState("chat/reply-suggestions-updated")
        }
        persist()
        return true
    }

    /** Queue one human turn for the on-device agent, optionally citing files imported into the workspace. */
    internal fun send(text: String, attachments: List<LocalImportedAttachment> = emptyList()) {
        val prompt = text.trim()
        if ((prompt.isEmpty() && attachments.isEmpty()) || _state.value.loading || !_state.value.configured) return
        cancelChatPostTurn()
        val attachmentBlock = attachments.joinToString("\n") { attachment ->
            val kind = if (attachment.mediaType.startsWith("image/")) "图片" else "文件"
            "- $kind：${attachment.name} → ${attachment.relativePath}（${attachment.bytes} B）"
        }
        val content = buildString {
            if (prompt.isNotEmpty()) append(prompt)
            if (attachments.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("本次附件已导入本机工作区：\n").append(attachmentBlock)
                if (attachments.any { it.mediaType.startsWith("image/") }) {
                    append("\n图片处理：支持图片输入的主模型会直接读取像素；若当前模型不支持，将使用 vision_analyze_file 分析上述工作区图片。")
                }
            }
        }
        val modelMessage = buildLocalUserModelMessage(content, attachments)
        queueHumanTurn(content, prompt, modelMessage)?.start()
    }

    /**
     * Edit any historical user turn and continue from that point.
     *
     * The active transcript is rewritten destructively: the original user turn and every later
     * message are removed from the active conversation. Hard scene state is replayed from the
     * retained prefix so deleted future locations cannot leak into the new continuation.
     */
    internal fun editAndResendUserMessage(messageId: String, replacement: String): LocalChatUserEditResult = synchronized(runStateLock) {
        val requestedText = replacement.trim()
        val state = _state.value
        if (state.usageMode != LocalUsageMode.CHAT || !state.configured) {
            return@synchronized LocalChatUserEditResult.UNAVAILABLE
        }
        if (
            state.loading ||
            state.running ||
            sessionTransitioning ||
            activeJob?.isCompleted == false ||
            pendingInputs.size() != 0
        ) return@synchronized LocalChatUserEditResult.BUSY

        val activeTranscript = activeTranscriptForUserEdit(
            messageId = messageId,
            activeBranch = activeChatBranchMessages(state.chatBranches),
            hotMessages = state.messages,
            loadDurableTranscript = { LocalSessionTranscriptPager(eventLog).all() },
        )
        val originalIndex = activeTranscript.indexOfFirst { message -> message.id == messageId }
        if (originalIndex < 0) return@synchronized LocalChatUserEditResult.MESSAGE_MISSING
        val original = activeTranscript[originalIndex]
        if (original.role != "user") return@synchronized LocalChatUserEditResult.MESSAGE_MISSING

        val content = withEditedChatUserText(original, requestedText)
        if (content.isBlank()) return@synchronized LocalChatUserEditResult.EMPTY
        if (editableChatUserText(original).trim() == requestedText) return@synchronized LocalChatUserEditResult.UNCHANGED
        cancelChatPostTurn()

        val sourceSequence = sourceEventSequenceForMessage(eventLog, messageId)
        val branchParentState = state.chatBranches.nodes
            .firstOrNull { node -> node.message.id == messageId }
            ?.parentId
            ?.let { parentId ->
                state.chatBranches.nodes.firstOrNull { node -> node.message.id == parentId }?.chatStateAfter
            }
        val baseState = branchParentState
            ?: restoreChatStateBefore(eventLog, json, sourceSequence, original.createdAt)
            ?: ChatCharacterState()
        val baseGroupState = if (state.groupChat.enabled) {
            restoreGroupStateBefore(eventLog, json, sourceSequence, original.createdAt)
                ?: state.groupChat.copy(
                    members = state.groupChat.members.map { member ->
                        member.copy(chatState = ChatCharacterState())
                    },
                    turnCursor = 0,
                )
        } else {
            state.groupChat
        }

        val discarded = activeTranscript.drop(originalIndex)
        memoryStore.rollbackSourceSessionFrom(
            sourceSessionId = state.sessionId,
            createdAtInclusive = original.createdAt,
            discardedMessageIds = discarded.mapTo(linkedSetOf(), LocalHarnessMessage::id),
        )
        if (!state.groupChat.enabled) {
            val galleryId = state.galleryId
            val storyId = state.galleryStoryId
            if (galleryId != null && storyId != null) {
                chatPersonaGalleryStore.excludeHistoryMessages(
                    id = galleryId,
                    storyId = storyId,
                    messageKeys = discarded.map { com.labteto.dshmobile.local.chat.galleryMessageArchiveKey(it) },
                    replacementChatState = baseState,
                )
            }
        } else {
            baseGroupState.members.forEach { member ->
                chatPersonaGalleryStore.replaceGroupChatState(member.galleryId, member.chatState)
            }
        }

        val edited = transcriptRuntime.newMessage("user", content)
        val rewritten = rewriteChatTranscriptFromUserEdit(
            activeMessages = activeTranscript,
            originalMessageId = messageId,
            editedMessage = edited,
        ) ?: return@synchronized LocalChatUserEditResult.MESSAGE_MISSING
        val retainedPrefix = rewritten.dropLast(1)

        val previousGeneration = if (state.groupChat.enabled) {
            state.groupChat.context.generation
        } else {
            state.chatContext.generation
        }
        val replayedContext = replayHardChatContextFromTranscript(
            messages = retainedPrefix,
            generation = previousGeneration + 1L,
        )
        val baseContext = replayedContext.copy(continuity = baseState.continuity)

        val editedModelMessage = editedChatUserModelMessage(
            eventLog = eventLog,
            originalMessageId = messageId,
            content = content,
        )
        modelHistory.reset(
            buildEditedChatModelHistory(
                eventLog = eventLog,
                messages = rewritten,
                groupMode = state.groupChat.enabled,
                editedMessageId = edited.id,
                editedModelMessage = editedModelMessage,
                systemPrompt = if (state.groupChat.enabled) groupChatSystemPrompt() else chatSystemPrompt(),
            ),
        )
        updateContextMetrics()

        val restoredGroupState = if (state.groupChat.enabled) {
            baseGroupState.copy(context = baseContext)
        } else {
            baseGroupState
        }
        persistChatTimelineBaseline(
            eventLog,
            json,
            state.copy(
                chatState = baseState.copy(scene = baseContext.scene, continuity = baseContext.continuity),
                chatContext = baseContext,
                groupChat = restoredGroupState,
            ),
        )

        val userEvent = eventLog.append("user/message", buildJsonObject {
            put("content", content)
            put("model_message", editedModelMessage)
            put("edited_from", messageId)
            put("queued", false)
            put("transcript", encodeTranscriptMessages(listOf(edited)))
        })
        transcriptProjectionCursor = maxOf(transcriptProjectionCursor ?: -1L, userEvent.sequence)

        _state.update { current ->
            current.copy(
                messages = rewritten.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES),
                transcriptIndex = buildLocalTranscriptRuntimeIndex(rewritten),
                chatState = baseState.copy(
                    scene = if (current.groupChat.enabled) ChatSceneState() else baseContext.scene,
                    continuity = if (current.groupChat.enabled) ChatContinuityState() else baseContext.continuity,
                ),
                chatContext = if (current.groupChat.enabled) current.chatContext else baseContext,
                groupChat = restoredGroupState,
                replySuggestions = emptyList(),
                chatBranches = LocalChatBranchState(),
                groupActiveSpeakerName = null,
                personaCorrectionNotice = null,
                error = null,
            )
        }
        val rewrittenTranscriptSequence = persistRewrittenChatTranscript(
            eventLog = eventLog,
            reason = "user-edited",
            activeTranscript = rewritten,
        )
        transcriptProjectionCursor = maxOf(
            transcriptProjectionCursor ?: -1L,
            rewrittenTranscriptSequence,
        )
        checkpointModelHistory(if (state.groupChat.enabled) "group/user-edited" else "chat/user-edited")
        persist()
        automationScheduler.onChatUserActivity(
            sessionId = state.sessionId,
            userMessageAt = edited.createdAt,
        )

        scope.launch(start = CoroutineStart.LAZY) {
            if (state.groupChat.enabled) {
                captureGroupPersonaCorrections(requestedText)
                runGroupChatTurn(content)
            } else {
                captureChatPersonaCorrection(requestedText)
                hydrateNewChatStateFromRelationshipMemory()
                runChatTurn(content, sourceMessageId = edited.id)
            }
        }.also { activeJob = it; it.start() }
        LocalChatUserEditResult.SENT
    }

    /** Switch among saved alternatives for one user or assistant turn. */
    internal fun selectChatMessageVariant(messageId: String, targetIndex: Int): Boolean = synchronized(runStateLock) {
        val state = _state.value
        if (
            state.usageMode != LocalUsageMode.CHAT ||
            state.groupChat.enabled ||
            state.loading ||
            sessionTransitioning ||
            activeJob?.isCompleted == false ||
            pendingInputs.size() != 0 ||
            !state.transcriptIndex.branchingEligible
        ) return@synchronized false

        val selected = selectChatBranchVariant(state.chatBranches, messageId, targetIndex)
            ?: return@synchronized false
        val activeMessages = activeChatBranchMessages(selected)
        if (activeMessages.isEmpty() || !chatBranchingEligible(activeMessages)) return@synchronized false

        val snapshot = chatBranchLastSnapshot(selected)
        val selectedContext = restoreBranchContext(
            snapshot = chatBranchLastContext(selected),
            legacyState = snapshot?.first,
            previousGeneration = state.chatContext.generation,
        )
        modelHistory.reset(
            buildDurableChatModelHistory(
                eventLog = eventLog,
                messages = activeMessages,
                systemPrompt = chatSystemPrompt(),
            ),
        )
        updateContextMetrics()
        _state.update { current ->
            current.copy(
                messages = activeMessages.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES),
                transcriptIndex = buildLocalTranscriptRuntimeIndex(activeMessages),
                chatState = snapshot?.first ?: current.chatState,
                chatContext = selectedContext,
                replySuggestions = snapshot?.second.orEmpty(),
                chatBranches = selected,
                error = null,
            )
        }
        persistChatBranchState("variant-selected")
        val activeTranscriptSequence = persistActiveChatTranscript(
            eventLog = eventLog,
            reason = "variant-selected",
            activeTranscript = activeMessages,
        )
        transcriptProjectionCursor = maxOf(
            transcriptProjectionCursor ?: -1L,
            activeTranscriptSequence,
        )
        checkpointModelHistory("chat/variant-selected")
        persist()
        true
    }

    /** Re-run the latest answer against the same turn; never re-execute work tools. */
    internal fun regenerateReply(messageId: String): Boolean = synchronized(runStateLock) {
        val state = _state.value
        if (!state.configured || state.loading ||
            state.groupChat.enabled ||
            sessionTransitioning || activeJob?.isCompleted == false || pendingInputs.size() != 0
        ) return@synchronized false
        val last = state.messages.lastOrNull() ?: return@synchronized false
        if (last.id != messageId || last.role != "assistant") return@synchronized false
        val promptMessage = state.messages.dropLast(1).lastOrNull { it.role == "user" }
            ?: return@synchronized false
        val prompt = promptMessage.content
        if (modelHistory.lastOrNull()?.get("role")?.jsonPrimitive?.contentOrNull != "assistant") {
            return@synchronized false
        }
        cancelChatPostTurn()

        if (state.usageMode == LocalUsageMode.CHAT && state.transcriptIndex.branchingEligible) {
            val branches = if (state.chatBranches.nodes.isNotEmpty()) {
                state.chatBranches
            } else {
                syncChatBranchState(
                    current = LocalChatBranchState(),
                    activeMessages = transcriptForBranchMaterialization(state),
                    chatState = state.chatState,
                    chatContext = state.chatContext,
                    replySuggestions = state.replySuggestions,
                )
            }
            val branchParentState = chatBranchParentState(branches, messageId)
            val sourceSequence = sourceEventSequenceForMessage(eventLog, promptMessage.id)
            val baseState = branchParentState
                ?: restoreChatStateBefore(eventLog, json, sourceSequence, promptMessage.createdAt)
                ?: ChatCharacterState()
            val baseContext = restoreBranchContext(
                snapshot = chatBranchParentContext(branches, messageId),
                legacyState = baseState,
                previousGeneration = state.chatContext.generation,
            )
            _state.update {
                it.copy(
                    chatState = baseState.copy(
                        scene = baseContext.scene,
                        continuity = baseContext.continuity,
                    ),
                    chatContext = baseContext,
                    replySuggestions = emptyList(),
                    chatBranches = branches,
                )
            }
        }
        scope.launch(start = CoroutineStart.LAZY) {
            if (state.usageMode == LocalUsageMode.CHAT) runChatTurn(prompt, replacingMessageId = messageId)
            else regenerateWorkReply(messageId)
        }
            .also { activeJob = it; it.start() }
        true
    }

    private fun transcriptForBranchMaterialization(
        state: LocalHarnessState,
    ): List<LocalHarnessMessage> {
        val activeBranch = activeChatBranchMessages(state.chatBranches)
        if (activeBranch.isNotEmpty()) return activeBranch
        if (state.transcriptIndex.totalMessageCount <= state.messages.size.toLong()) return state.messages
        return LocalSessionTranscriptPager(eventLog).all()
    }

    private fun persistChatBranchState(reason: String) {
        val state = _state.value
        eventLog.append("chat/branch-state", JsonObject(
            encodeChatBranchStateEvent(state.chatBranches) + ("reason" to JsonPrimitive(reason)),
        ))
        val activeTranscript = activeChatBranchMessages(state.chatBranches)
            .ifEmpty { state.messages }
        val transcriptEvent = eventLog.append("chat/active-transcript", buildJsonObject {
            put("reason", reason)
            put("transcript", encodeTranscriptMessages(activeTranscript))
        })
        transcriptProjectionCursor = maxOf(transcriptProjectionCursor ?: -1L, transcriptEvent.sequence)
    }

    private suspend fun regenerateWorkReply(messageId: String) {
        _state.update { it.copy(running = true, error = null) }
        try {
            val snapshot = _state.value
            val key = apiKeys.get() ?: error("请先配置模型密钥")
            val messages = withEphemeralContext(
                modelHistory.dropLast(1),
                "基于本轮已有结果重写最终回复；不要调用工具或声称重新执行。",
            )
            val reply = completeWithRetry(
                key = key,
                snapshot = snapshot,
                messages = messages,
                step = 1,
                toolsOverride = JsonArray(emptyList()),
                publishPreview = false,
                persistOverflowHistory = true,
            )
            val content = reply.content?.takeIf(String::isNotBlank) ?: error("模型没有返回可用回复")
            usageTracker.record(snapshot.model, reply.usage)
            val transcript = listOf(transcriptRuntime.newMessage("assistant", content))
            val data = transcriptRuntime.withTranscript(reply.message, transcript)
            val event = eventLog.append("assistant/message", JsonObject(
                data + ("replaces" to JsonPrimitive(messageId)),
            ))
            modelHistory.reset(modelHistory.dropLast(1))
            modelHistory.append(reply.message)
            updateContextMetrics()
            _state.update {
                val retained = it.messages.filterNot { message -> message.id == messageId }
                it.copy(
                    messages = retained,
                    transcriptIndex = it.transcriptIndex.copy(
                        totalMessageCount = (it.transcriptIndex.totalMessageCount - 1L)
                            .coerceAtLeast(0L),
                    ),
                )
            }
            transcriptRuntime.applyMessages(transcript, event.sequence, clearStreamingPreview = true)
            checkpointModelHistory("work/regenerated")
            persist()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(error = error.message ?: "重新生成失败") }
        } finally {
            _state.update { it.copy(running = false) }
            _streamingState.value = LocalHarnessStreamingState()
            val completedJob = currentCoroutineContext()[Job]
            synchronized(runStateLock) { if (activeJob === completedJob) activeJob = null }
        }
    }

    private fun queueHumanTurn(
        content: String,
        memoryInput: String = content,
        modelMessage: JsonObject? = null,
    ): Job? = synchronized(runStateLock) {
        if (sessionTransitioning) return@synchronized null
        if (_state.value.usageMode == LocalUsageMode.WORK) {
            return@synchronized queueWorkTurnLocked(content, memoryInput, modelMessage)
        }
        if (activeJob?.isCompleted == false) {
            val queuedInput = QueuedAgentInput(
                content = content,
                memoryInput = memoryInput,
                modelMessage = modelMessage,
                id = UUID.randomUUID().toString(),
            )
            val accepted = pendingInputs.offer(queuedInput)
            if (!accepted) {
                _state.update { it.copy(error = "当前执行中的补充消息已达到 ${MAX_PENDING_INPUTS} 条上限") }
                return@synchronized null
            }
            recordUserTranscript(
                content = content,
                modelMessage = modelMessage,
                queued = true,
                queuedInput = queuedInput,
            )
            _state.update { it.copy(queuedInputCount = pendingInputs.size()) }
            persist()
            return@synchronized null
        }
        queueTurnLocked(content, memoryInput, modelMessage)
    }

    private fun queueWorkTurnLocked(
        content: String,
        memoryInput: String,
        modelMessage: JsonObject?,
    ): Job? {
        val sessionId = currentSessionId
        val existing = activeWorkRuns[sessionId]
        if (existing?.job?.isCompleted == false) {
            val queuedInput = QueuedAgentInput(
                content = content,
                memoryInput = memoryInput,
                modelMessage = modelMessage,
                id = UUID.randomUUID().toString(),
            )
            if (!existing.pendingInputs.offer(queuedInput)) {
                existing.state.update {
                    it.copy(error = "当前执行中的补充消息已达到 ${MAX_PENDING_INPUTS} 条上限")
                }
                return null
            }
            recordUserTranscript(
                content = content,
                modelMessage = modelMessage,
                queued = true,
                queuedInput = queuedInput,
                binding = existing,
            )
            existing.state.update { it.copy(queuedInputCount = existing.pendingInputs.size()) }
            persist(existing)
            return null
        }

        val durableMessage = modelMessage ?: buildJsonObject {
            put("role", "user")
            put("content", content)
        }
        val sourceMessageId = recordUserTranscript(content, durableMessage, queued = false)
        appendUserToModelHistory(durableMessage)
        persist()

        val binding = LocalWorkRunBinding(
            sessionId = sessionId,
            initialState = _state.value.copy(running = true, error = null),
            initialHistory = modelHistory.snapshot(),
            eventLog = eventLogFor(sessionId),
            initialTranscriptProjectionCursor = transcriptProjectionCursor,
            maxPendingInputs = MAX_PENDING_INPUTS,
            pruneToolResult = ::pruneToolResult,
        )
        activeWorkRuns[sessionId] = binding
        binding.mirrorJob = scope.launch {
            binding.state.collect {
                mirrorWorkRunState(binding)
            }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runAgentTurn(
                input = content,
                memoryInput = memoryInput,
                sourceMessageId = sourceMessageId,
                binding = binding,
            )
        }
        binding.job = job
        return job
    }

    private fun mirrorWorkRunState(binding: LocalWorkRunBinding) {
        if (currentSessionId != binding.sessionId || _state.value.sessionId != binding.sessionId) return
        val run = binding.state.value
        _state.update { visible ->
            if (visible.sessionId != binding.sessionId) {
                visible
            } else {
                visible.copy(
                    messages = run.messages,
                    transcriptIndex = run.transcriptIndex,
                    plan = run.plan,
                    todos = run.todos,
                    goal = run.goal,
                    planMode = run.planMode,
                    running = run.running,
                    pendingApproval = run.pendingApproval,
                    pendingQuestion = run.pendingQuestion,
                    queuedInputCount = run.queuedInputCount,
                    workflowProgress = run.workflowProgress,
                    contextChars = run.contextChars,
                    contextBudgetChars = run.contextBudgetChars,
                    error = run.error,
                )
            }
        }
    }

    private fun syncVisibleWorkRun(sessionId: String) {
        val binding = activeWorkRuns[sessionId] ?: return
        modelHistory.reset(binding.modelHistory.snapshot())
        transcriptProjectionCursor = binding.transcriptProjectionCursor
        mirrorWorkRunState(binding)
    }

    private fun queueTurn(
        content: String,
        memoryInput: String = content,
        modelMessage: JsonObject? = null,
    ): Job? = synchronized(runStateLock) {
        if (sessionTransitioning || activeJob?.isCompleted == false) return@synchronized null
        queueTurnLocked(content, memoryInput, modelMessage)
    }

    private fun queueTurnLocked(
        content: String,
        memoryInput: String,
        modelMessage: JsonObject?,
    ): Job {
        val durableMessage = modelMessage ?: buildJsonObject {
            put("role", "user")
            put("content", content)
        }
        val sourceMessageId = recordUserTranscript(content, durableMessage, queued = false)
        appendUserToModelHistory(durableMessage)
        persist()
        return scope.launch(start = CoroutineStart.LAZY) {
            runTurn(content, memoryInput, sourceMessageId)
        }.also { activeJob = it }
    }

    private fun recordUserTranscript(
        content: String,
        modelMessage: JsonObject?,
        queued: Boolean,
        queuedInput: QueuedAgentInput? = null,
        binding: LocalWorkRunBinding? = null,
    ): String {
        val targetState = binding?.state ?: _state
        val targetLog = binding?.eventLog ?: eventLog
        val targetTranscript = binding?.transcriptRuntime ?: transcriptRuntime
        val targetPending = binding?.pendingInputs ?: pendingInputs
        val before = targetState.value
        persistChatTimelineBaseline(targetLog, json, before)
        val durableInput = queuedInput.takeIf { queued }
        val transcriptMessage = targetTranscript.newMessage("user", content).let { message ->
            durableInput?.id
                ?.takeIf(String::isNotBlank)
                ?.let { durableId -> message.copy(id = durableId) }
                ?: message
        }
        val userEvent = if (queued) {
            val durableInput = requireNotNull(durableInput) { "排队消息缺少持久编号" }
            targetLog.append(
                LOCAL_AGENT_INBOX_EVENT_TYPE,
                encodeLocalAgentInboxEvent(
                    action = "queued",
                    pending = targetPending.snapshot(),
                    affected = listOf(durableInput),
                    transcript = listOf(transcriptMessage),
                ),
            )
        } else {
            targetLog.append("user/message", buildJsonObject {
                put("content", content)
                modelMessage?.let { put("model_message", it) }
                put("queued", false)
                put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
            })
        }
        targetTranscript.applyMessages(listOf(transcriptMessage), userEvent.sequence)
        if (before.usageMode == LocalUsageMode.CHAT && !before.groupChat.enabled) {
            automationScheduler.onChatUserActivity(
                sessionId = before.sessionId,
                userMessageAt = transcriptMessage.createdAt,
            )
        }
        if (
            before.usageMode == LocalUsageMode.CHAT &&
            before.chatBranches.nodes.isNotEmpty() &&
            before.transcriptIndex.branchingEligible
        ) {
            val branches = appendMaterializedChatBranchMessage(
                current = before.chatBranches,
                activeMessages = before.messages,
                message = transcriptMessage,
                parentId = before.transcriptIndex.latestDialogueMessageId,
                chatState = before.chatState,
                chatContext = before.chatContext,
                replySuggestions = before.replySuggestions,
            )
            targetState.update {
                it.copy(
                    replySuggestions = emptyList(),
                    chatBranches = branches,
                )
            }
        } else if (before.usageMode == LocalUsageMode.CHAT) {
            targetState.update { it.copy(replySuggestions = emptyList()) }
        }
        return transcriptMessage.id
    }

    private fun appendUserToModelHistory(
        message: JsonObject,
        binding: LocalWorkRunBinding? = null,
    ) {
        (binding?.modelHistory ?: modelHistory).append(message)
        updateContextMetrics(binding)
    }

    internal suspend fun runAutomationPrompt(
        text: String,
        timeoutMillis: Long = 5 * 60_000L,
    ): String = automationWorkCoordinator.runPrompt(text, timeoutMillis)

    internal suspend fun prepareAutomationWorkSession(
        text: String,
        preferredSessionId: String? = null,
    ): String = automationWorkCoordinator.prepareWorkSession(text, preferredSessionId)

    internal suspend fun runAutomationWork(
        text: String,
        preferredSessionId: String? = null,
        timeoutMillis: Long = 5 * 60_000L,
        recoverInterrupted: Boolean = false,
    ): LocalAutomationRunResult = automationWorkCoordinator.runWork(
        text = text,
        preferredSessionId = preferredSessionId,
        timeoutMillis = timeoutMillis,
        recoverInterrupted = recoverInterrupted,
    )

    /**
     * Generate one role-authored message for an existing single-character chat session.
     *
     * The scheduled instruction is context, never a fabricated user turn. When the target chat is
     * visible we temporarily own the normal turn slot so user input queues behind the proactive
     * message instead of racing it; detached chats are generated without changing the visible UI.
     */
    internal suspend fun runAutomationChat(
        instruction: String,
        targetSessionId: String,
        timeoutMillis: Long = 3 * 60_000L,
        recoverInterrupted: Boolean = false,
        recoveryStartedAt: Long? = null,
        quietHoursEnabled: Boolean = false,
        quietStartHour: Int = 23,
        quietStartMinute: Int = 0,
        quietEndHour: Int = 7,
        quietEndMinute: Int = 0,
        proactiveMinGapMinutes: Long = 6L * 60L,
        proactiveMaxUnanswered: Int = 2,
        minimumSilenceMinutes: Long? = null,
        silenceReferenceAt: Long? = null,
        bypassProactivePolicy: Boolean = false,
    ): LocalAutomationRunResult = automationChatCoordinator.run(
        instruction = instruction,
        targetSessionId = targetSessionId,
        timeoutMillis = timeoutMillis,
        recoverInterrupted = recoverInterrupted,
        recoveryStartedAt = recoveryStartedAt,
        quietHoursEnabled = quietHoursEnabled,
        quietStartHour = quietStartHour,
        quietStartMinute = quietStartMinute,
        quietEndHour = quietEndHour,
        quietEndMinute = quietEndMinute,
        proactiveMinGapMinutes = proactiveMinGapMinutes,
        proactiveMaxUnanswered = proactiveMaxUnanswered,
        minimumSilenceMinutes = minimumSilenceMinutes,
        silenceReferenceAt = silenceReferenceAt,
        bypassProactivePolicy = bypassProactivePolicy,
    )

    private suspend fun acquireAutomationChatVisibleTurn(
        targetSessionId: String,
        automationJob: Job?,
    ): Boolean {
        if (_state.value.sessionId != targetSessionId) return false
        var ownsVisibleTurn = false
        withTimeout(60_000L) {
            while (!ownsVisibleTurn) {
                ownsVisibleTurn = synchronized(runStateLock) {
                    val busy = sessionTransitioning ||
                        _state.value.running ||
                        activeJob?.isCompleted == false
                    if (!busy) {
                        activeJob = automationJob
                        true
                    } else {
                        false
                    }
                }
                if (!ownsVisibleTurn) delay(100)
            }
        }
        _state.update { current ->
            if (current.sessionId == targetSessionId) {
                current.copy(running = true, error = null)
            } else {
                current
            }
        }
        return ownsVisibleTurn
    }

    private fun commitVisibleAutomationChatReply(
        session: LocalHarnessSession,
        reply: LocalModelReply,
        content: String,
        proactiveMessage: LocalHarnessMessage,
        assistantEventSequence: Long,
    ) {
        val beforeProactive = _state.value
        val nextChatState = chatTurnCoordinator.applyDeterministicInteractionState(
            previous = beforeProactive.chatState,
            userMessage = "",
            assistantMessage = content,
        )
        modelHistory.append(reply.message)
        updateContextMetrics()
        transcriptRuntime.applyMessages(
            listOf(proactiveMessage),
            assistantEventSequence,
            clearStreamingPreview = true,
        )
        _state.update { current ->
            if (current.sessionId != session.id) {
                current
            } else {
                val baseContext = current.chatContext
                    .withLegacyFallback(nextChatState)
                    .applySceneTurn(
                        userMessage = "",
                        assistantMessage = content,
                        sequence = assistantEventSequence,
                    )
                val pending = ChatPendingTurn(
                    sequence = assistantEventSequence,
                    assistantMessageId = proactiveMessage.id,
                    branchHeadId = proactiveMessage.id,
                    userMessage = "",
                    assistantMessage = content,
                    generation = baseContext.generation,
                )
                current.copy(
                    chatState = nextChatState,
                    chatContext = baseContext.enqueuePending(pending),
                )
            }
        }
        if (
            pendingInputs.size() == 0 &&
            beforeProactive.transcriptIndex.branchingEligible &&
            beforeProactive.chatBranches.nodes.isNotEmpty()
        ) {
            _state.update { current ->
                current.copy(
                    chatBranches = appendMaterializedChatBranchMessage(
                        current = current.chatBranches,
                        activeMessages = emptyList(),
                        message = proactiveMessage,
                        parentId = beforeProactive.transcriptIndex.latestDialogueMessageId,
                        chatState = current.chatState,
                        chatContext = current.chatContext,
                        replySuggestions = current.replySuggestions,
                    ),
                )
            }
        }
        checkpointModelHistory("chat/proactive-automation")
        persist()
    }

    private fun releaseAutomationChatVisibleTurn(
        targetSessionId: String,
        automationJob: Job?,
    ) {
        _state.update { current ->
            if (current.sessionId == targetSessionId) {
                current.copy(
                    running = false,
                    pendingApproval = null,
                    pendingQuestion = null,
                    deviceApprovalLease = false,
                )
            } else {
                current
            }
        }
        synchronized(runStateLock) {
            if (activeJob === automationJob) activeJob = null
        }
        startNextQueuedTurnIfIdle()?.start()
    }


    /** Copy a picked image/file into the app-private workspace before the model sees it. */
    internal suspend fun importAttachment(uri: Uri): LocalImportedAttachment =
        withContext(Dispatchers.IO) { attachmentImporter.import(uri) }

    internal suspend fun diagnoseNetwork(target: String): String = web.diagnose(target)

    internal suspend fun sessionStorageStatusForUi(): LocalSessionStorageStatus =
        withContext(Dispatchers.IO) { sessionStorageManager.status() }

    internal suspend fun compactSessionStorageForUi(): LocalSessionStorageStatus =
        withContext(Dispatchers.IO) { sessionStorageManager.compactAll() }

    internal suspend fun exportSessionStorageForUi(output: OutputStream): Long =
        withContext(Dispatchers.IO) { sessionStorageManager.exportAll(output) }

    internal suspend fun environmentInfoForUi(): String = withContext(Dispatchers.IO) {
        environmentInfo()
    }

    internal suspend fun diagnosticReportForUi(): String = withContext(Dispatchers.IO) {
        DiagnosticReport.build(AppLog.snapshot(), environmentInfo())
    }

    internal suspend fun githubConnectorConfiguredForUi(): Boolean = githubCredentials.configured()
    internal suspend fun configureGitHubConnectorForUi(token: String): GitHubConnectorStatus =
        githubPlugin.validateCredential(token).also { githubCredentials.put(token) }
    internal suspend fun clearGitHubConnectorForUi() = githubCredentials.clear()

    internal suspend fun mcpServersForUi(): List<McpServerSnapshot> = mcpPlugin.serverSnapshots()

    internal suspend fun connectMcpHttpForUi(serverId: String, endpoint: String): String =
        mcpPlugin.connectHttpFromUi(pluginRegistry.context, serverId, endpoint)

    internal suspend fun connectMcpStdioForUi(
        serverId: String,
        command: List<String>,
        workingDirectory: String? = null,
    ): String = mcpPlugin.connectStdioFromUi(
        pluginRegistry.context,
        serverId,
        command,
        workingDirectory,
    )

    internal suspend fun disconnectMcpForUi(serverId: String): String =
        mcpPlugin.disconnectFromUi(pluginRegistry.context, serverId)

    internal fun installedPluginIdsForUi(): List<String> = pluginRegistry.ids()

    internal fun backgroundJobOutputForUi(jobId: String): String = jobs.output(jobId)

    internal fun stopBackgroundJobForUi(jobId: String): String = jobs.kill(jobId)

    /** Resolve the current write or shell approval. */
    internal fun answerApproval(callId: String, approved: Boolean) =
        approvalCoordinator.answerApproval(callId, approved)

    internal fun enableAutoApproval() =
        approvalCoordinator.enableAutoApproval()

    internal fun enableAutoApprovalForPending(callId: String) =
        approvalCoordinator.enableAutoApprovalForPending(callId)

    internal fun enableDeviceApprovalLease(callId: String) =
        approvalCoordinator.enableDeviceApprovalLease(callId)

    internal fun disableDeviceApprovalLease() =
        approvalCoordinator.disableDeviceApprovalLease()

    internal fun disableAutoApproval() =
        approvalCoordinator.disableAutoApproval()

    /** Resolve the current model-authored question. */
    internal fun answerQuestion(callId: String, answer: String) =
        approvalCoordinator.answerQuestion(callId, answer)

    /** Resolve a dismissed ask-user request with one stable model-visible semantic. */
    internal fun cancelQuestion(callId: String) =
        approvalCoordinator.cancelQuestion(callId)

    /** Stop the active model/tool turn. New work stays blocked until cleanup completes. */
    internal fun stop() {
        cancelChatPostTurn()
        interactions.cancelAll()
        val running = synchronized(runStateLock) {
            val discarded = pendingInputs.drain()
            if (discarded.isNotEmpty()) {
                eventLog.append(
                    LOCAL_AGENT_INBOX_EVENT_TYPE,
                    encodeLocalAgentInboxEvent(
                        action = "cancelled",
                        pending = pendingInputs.snapshot(),
                        affected = discarded,
                    ),
                )
            }
            _state.update { it.copy(queuedInputCount = 0) }
            activeJob
        }
        running?.cancel()
        // Keep running=true until runTurn's finally has completed. Otherwise the
        // composer looks available during cancellation even though queueTurn
        // correctly still rejects a replacement turn.
        _state.update { it.copy(pendingApproval = null, pendingQuestion = null) }
    }

    /** Start a clean, project-scoped, or continuation session without copying full old history. */
    internal fun createSession(mode: LocalConversationMode) =
        sessionLifecycle.createSession(mode)

    internal fun createSession(
        mode: LocalConversationMode,
        usageMode: LocalUsageMode,
        galleryEntry: PersonaGalleryEntry? = null,
        galleryStoryId: String? = null,
        freshGalleryStory: Boolean = false,
        chatMode: LocalChatMode? = null,
    ) = sessionLifecycle.createSession(
        mode = mode,
        usageMode = usageMode,
        galleryEntry = galleryEntry,
        galleryStoryId = galleryStoryId,
        freshGalleryStory = freshGalleryStory,
        chatMode = chatMode,
    )

    /** Backward-compatible entry point: a plain new session is fully independent. */
    internal fun newSession() = sessionLifecycle.createSession(LocalConversationMode.INDEPENDENT)

    internal fun switchChatMode(mode: LocalChatMode) =
        sessionLifecycle.switchChatMode(mode)

    /** Move between product surfaces; the Chat pill always returns to normal one-to-one chat. */
    internal fun switchUsageMode(mode: LocalUsageMode) =
        sessionLifecycle.switchUsageMode(mode)

    internal fun switchSession(sessionId: String) =
        sessionLifecycle.switchSession(sessionId)

    /** Permanently remove selected local sessions and their durable event segments. */
    internal suspend fun deleteSessions(requestedIds: Set<String>): Int =
        sessionLifecycle.deleteSessions(requestedIds)

    /** Remove the local API key after an in-flight turn has finished cancelling. */
    internal fun clearCredential() {
        if (!beginSessionTransition()) return
        _state.update { it.copy(loading = true) }
        scope.launch {
            sessionTransitionMutex.withLock {
                try {
                    cancelActiveRunAndJoin()
                    val current = _state.value
                    val result = modelConfiguration.clearActive(current.model, current.baseUrl)
                    _state.update {
                        it.copy(
                            configured = result.configured,
                            model = result.model,
                            baseUrl = result.baseUrl,
                            modelProfiles = result.profiles,
                            configuredModels = result.configuredModels,
                        )
                    }
                } finally {
                    endSessionTransition()
                    _state.update { it.copy(loading = false) }
                }
            }
        }
    }

    private fun isRunBusy(): Boolean = synchronized(runStateLock) {
        sessionTransitioning || activeJob?.isCompleted == false
    }

    private fun beginSessionTransition(): Boolean {
        val started = synchronized(runStateLock) {
            if (sessionTransitioning) return@synchronized false
            sessionTransitioning = true
            true
        }
        if (started) cancelChatPostTurn()
        return started
    }

    private fun endSessionTransition() {
        synchronized(runStateLock) { sessionTransitioning = false }
    }

    private suspend fun cancelActiveRunAndJoin() {
        interactions.cancelAll()
        val job = synchronized(runStateLock) {
            val discarded = pendingInputs.drain()
            if (discarded.isNotEmpty()) {
                eventLog.append(
                    LOCAL_AGENT_INBOX_EVENT_TYPE,
                    encodeLocalAgentInboxEvent(
                        action = "cancelled",
                        pending = pendingInputs.snapshot(),
                        affected = discarded,
                    ),
                )
            }
            _state.update { it.copy(queuedInputCount = 0) }
            activeJob
        }
        job?.cancelAndJoin()
        synchronized(runStateLock) {
            if (activeJob === job) activeJob = null
        }
    }

    private suspend fun captureAutoMemoryDirective(
        text: String,
        sourceMessageId: String? = null,
    ) = memoryCoordinator.captureAutoMemoryDirective(text, sourceMessageId)

    private fun chatRelationshipMemoryContext(
        query: String,
        snapshot: LocalHarnessState,
    ): String = memoryCoordinator.chatRelationshipMemoryContext(query, snapshot)

    private fun hydrateNewChatStateFromRelationshipMemory() =
        memoryCoordinator.hydrateNewChatStateFromRelationshipMemory()

    private suspend fun drainPendingInputsIntoHistory() {
        val queued = pendingInputs.drain()
        if (queued.isEmpty()) return
        val durableMessages = mutableListOf<JsonObject>()
        queued.forEach { input ->
            val durableMessage = input.modelMessage ?: buildJsonObject {
                put("role", "user")
                put("content", input.content)
            }
            modelHistory.append(durableMessage)
            durableMessages += durableMessage
            captureAutoMemoryDirective(input.memoryInput, input.id)
        }
        _state.update { it.copy(queuedInputCount = pendingInputs.size()) }
        eventLog.append(
            LOCAL_AGENT_INBOX_EVENT_TYPE,
            encodeLocalAgentInboxEvent(
                action = "claimed",
                pending = pendingInputs.snapshot(),
                affected = queued,
                modelMessages = durableMessages,
            ),
        )
        updateContextMetrics()
        persist()
    }

    private fun startNextQueuedTurnIfIdle(): Job? = synchronized(runStateLock) {
        if (sessionTransitioning || activeJob?.isCompleted == false) return@synchronized null
        val next = pendingInputs.poll() ?: return@synchronized null
        val durableMessage = next.modelMessage ?: buildJsonObject {
            put("role", "user")
            put("content", next.content)
        }
        _state.update { it.copy(queuedInputCount = pendingInputs.size()) }
        appendUserToModelHistory(durableMessage)
        eventLog.append(
            LOCAL_AGENT_INBOX_EVENT_TYPE,
            encodeLocalAgentInboxEvent(
                action = "resumed",
                pending = pendingInputs.snapshot(),
                affected = listOf(next),
                modelMessages = listOf(durableMessage),
            ),
        )
        persist()
        scope.launch(start = CoroutineStart.LAZY) {
            runTurn(next.content, next.memoryInput, next.id)
        }.also { activeJob = it }
    }

    /** Switch between inspection-only planning and normal execution. */
    internal fun setPlanMode(enabled: Boolean) {
        if (_state.value.usageMode == LocalUsageMode.CHAT || isRunBusy()) return
        _state.update { it.copy(planMode = enabled) }
        eventLog.append("plan/mode", buildJsonObject { put("active", enabled) })
        if (modelHistory.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
            val prompt = systemPrompt()
            modelHistory.replaceSystem(
                buildJsonObject { put("role", "system"); put("content", prompt) },
            )
            eventLog.append("system/prompt", buildJsonObject { put("content", prompt) })
            updateContextMetrics()
        }
        persist()
    }

    private suspend fun runTurn(
        input: String,
        memoryInput: String = input,
        sourceMessageId: String? = null,
    ) {
        val snapshot = _state.value
        if (snapshot.usageMode == LocalUsageMode.CHAT && snapshot.groupChat.enabled) {
            captureGroupPersonaCorrections(memoryInput)
            runGroupChatTurn(input, sourceMessageId)
            return
        }
        if (snapshot.usageMode == LocalUsageMode.CHAT) {
            captureChatPersonaCorrection(memoryInput)
            hydrateNewChatStateFromRelationshipMemory()
        }
        runAgentTurn(input, memoryInput, sourceMessageId)
    }

    private fun captureChatPersonaCorrection(text: String) {
        val snapshot = _state.value
        if (snapshot.usageMode != LocalUsageMode.CHAT || text.isBlank()) return
        val updated = chatPersonaStore.captureExplicitCorrection(snapshot.personaId, text) ?: return
        if (updated.corrections == snapshot.chatPersona.corrections) return

        val correction = updated.corrections.lastOrNull().orEmpty()
        val notice = ChatPersonaCorrectionNotice(
            id = System.nanoTime(),
            personaId = updated.id,
            correction = correction,
        )
        _state.update { current ->
            if (current.personaId == updated.id) {
                current.copy(chatPersona = updated, personaCorrectionNotice = notice)
            } else {
                current
            }
        }
        eventLog.append("chat/persona-correction", buildJsonObject {
            put("persona_id", updated.id)
            put("count", updated.corrections.size)
            put("latest", correction)
        })
        persist()
        scope.launch {
            delay(PERSONA_CORRECTION_UNDO_MILLIS)
            _state.update { current ->
                if (current.personaCorrectionNotice?.id == notice.id) {
                    current.copy(personaCorrectionNotice = null)
                } else {
                    current
                }
            }
        }
    }

    internal fun undoChatPersonaCorrection(noticeId: Long, personaId: String, correction: String) {
        val snapshot = _state.value
        val notice = snapshot.personaCorrectionNotice
        if (
            notice == null ||
            notice.id != noticeId ||
            notice.personaId != personaId ||
            notice.correction != correction
        ) return

        scope.launch {
            val updated = chatPersonaStore.removeCorrection(personaId, correction) ?: return@launch
            _state.update { current ->
                if (
                    current.personaId == personaId &&
                    current.personaCorrectionNotice?.id == noticeId
                ) {
                    current.copy(
                        chatPersona = updated,
                        personaCorrectionNotice = null,
                    )
                } else {
                    current
                }
            }
            eventLog.append("chat/persona-correction", buildJsonObject {
                put("persona_id", personaId)
                put("action", "undo")
                put("correction", correction)
            })
            persist()
        }
    }

    private fun captureGroupPersonaCorrections(text: String) {
        val snapshot = _state.value
        if (
            snapshot.usageMode != LocalUsageMode.CHAT ||
            !snapshot.groupChat.enabled ||
            text.isBlank()
        ) return

        snapshot.groupChat.members.forEach { member ->
            val current = chatPersonaStore.get(member.personaId)
            if (current.name.isBlank() || current.name !in text) return@forEach
            val updated = chatPersonaStore.captureExplicitCorrection(member.personaId, text)
                ?: return@forEach
            if (updated.corrections == current.corrections) return@forEach
            _state.update { state ->
                state.copy(
                    groupChat = state.groupChat.copy(
                        members = state.groupChat.members.map { existing ->
                            if (existing.galleryId == member.galleryId) {
                                existing.copy(
                                    displayName = updated.name,
                                    persona = updated,
                                )
                            } else {
                                existing
                            }
                        },
                    ),
                )
            }
            eventLog.append("group/persona-correction", buildJsonObject {
                put("gallery_id", member.galleryId)
                put("persona_id", member.personaId)
                put("count", updated.corrections.size)
                put("latest", updated.corrections.lastOrNull().orEmpty())
            })
        }
    }

    private fun rebuildGroupModelHistoryFromTranscript(messages: List<LocalHarnessMessage>) {
        val rebuilt = buildList {
            add(buildJsonObject {
                put("role", "system")
                put("content", groupChatSystemPrompt())
            })
            messages.forEach { message ->
                if (message.role == "user" || message.role == "assistant") {
                    add(buildJsonObject {
                        put("role", message.role)
                        put(
                            "content",
                            if (message.role == "assistant") groupTranscriptLine(message) else message.content,
                        )
                    })
                }
            }
        }
        modelHistory.reset(rebuilt)
        updateContextMetrics()
    }

    private fun refreshGroupModelSystemPrompt() {
        val system = buildJsonObject {
            put("role", "system")
            put("content", groupChatSystemPrompt())
        }
        if (modelHistory.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
            modelHistory.replaceSystem(system)
        } else {
            modelHistory.prepend(system)
        }
        updateContextMetrics()
    }

    private suspend fun generateGroupReply(
        key: String,
        snapshot: LocalHarnessState,
        baseHistory: List<JsonObject>,
        input: String,
        allMembers: List<LocalGroupChatMember>,
        member: LocalGroupChatMember,
        index: Int,
    ): GroupGeneratedReply {
        val persona = member.persona.takeUnless {
            it.id == PersonaProfile.DEFAULT_PERSONA_ID &&
                member.personaId != PersonaProfile.DEFAULT_PERSONA_ID
        } ?: chatPersonaStore.get(member.personaId)
        val startedAtNanos = System.nanoTime()
        val interactiveAttempts = snapshot.modelAttempts.coerceIn(1, 2)

        return try {
            val prompt = chatReplyCoordinator.buildGroupPrompt(
                persona = persona,
                member = member,
                input = input,
                allMembers = allMembers,
                handoffSummary = snapshot.handoffSummary,
                sharedContext = snapshot.groupChat.context,
                announcement = snapshot.groupChat.announcement,
                mayStaySilent = false,
                silentToken = GROUP_CHAT_SILENT_TOKEN,
            )
            val groupRequestHistory = withTailEphemeralContext(baseHistory, prompt)
            val groupImageMode = resolveLocalImageInputMode(
                snapshot.imageInputMode,
                imageCapabilities,
                snapshot.baseUrl,
                snapshot.model,
            )
            if (hasLocalImageRefs(groupRequestHistory) && groupImageMode == LocalImageInputMode.TOOL) {
                throw IllegalStateException("当前模型不支持图片理解，请切换支持图片的模型后重试。")
            }
            val requestMessages = prepareLocalMultimodalMessages(
                messages = groupRequestHistory,
                workspaceRoot = File(workspace.path),
                mode = groupImageMode,
                budget = imageRequestBudget,
            )
            val rawReply = completeWithRetry(
                key = key,
                snapshot = snapshot,
                messages = requestMessages,
                step = 100 + index,
                toolsOverride = JsonArray(emptyList()),
                publishPreview = false,
                maxAttemptsOverride = interactiveAttempts,
                temperature = CHAT_ROLEPLAY_TEMPERATURE,
            )
            val finalContent = chatReplyCoordinator.finalizeGroup(
                snapshot = snapshot,
                persona = persona,
                member = member,
                input = input,
                index = index,
                rawReply = rawReply,
                sharedContext = snapshot.groupChat.context,
                retryRaw = { repairHint ->
                    completeWithRetry(
                        key = key,
                        snapshot = snapshot,
                        messages = withTailEphemeralContext(requestMessages, repairHint),
                        step = 100 + index,
                        toolsOverride = JsonArray(emptyList()),
                        publishPreview = false,
                        maxAttemptsOverride = 1,
                        allowContextOverflowRecovery = false,
                        temperature = CHAT_ROLEPLAY_TEMPERATURE,
                    )
                },
                appendEvent = { type, data ->
                    eventLog.append(type, data)
                },
            )

            eventLog.append("group/agent-latency", buildJsonObject {
                put("gallery_id", member.galleryId)
                put("index", index)
                put("status", "success")
                put("elapsed_ms", (System.nanoTime() - startedAtNanos) / 1_000_000L)
            })
            GroupGeneratedReply(
                member = member,
                persona = persona,
                content = finalContent,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            eventLog.append("group/agent-latency", buildJsonObject {
                put("gallery_id", member.galleryId)
                put("index", index)
                put("status", "failed")
                put("elapsed_ms", (System.nanoTime() - startedAtNanos) / 1_000_000L)
            })
            val failure = if (hasLocalImageRefs(baseHistory) && imageInputUnsupported(error)) {
                imageCapabilities.markUnsupported(snapshot.baseUrl, snapshot.model)
                IllegalStateException("当前模型不支持图片理解，请切换支持图片的模型后重试。", error)
            } else {
                error
            }
            GroupGeneratedReply(
                member = member,
                persona = persona,
                failure = failure,
            )
        }
    }

    private suspend fun refreshGroupMemberState(
        member: LocalGroupChatMember,
        persona: PersonaProfile,
        userMessage: String,
        assistantMessage: String,
        sharedPending: List<ChatPendingTurn>,
        step: Int,
    ): ChatCharacterState? {
        val snapshot = _state.value
        val key = apiKeys.get() ?: return null
        val sharedContext = _state.value.groupChat.context.withLegacyFallback(member.chatState)
        val plannerState = member.chatState.withContextForPlanner(sharedContext)
        val prompt = buildString {
            appendLine(
                chatTurnCoordinator.postTurnPrompt(
                    persona = persona,
                    state = plannerState,
                    userMessage = userMessage,
                    assistantMessage = assistantMessage,
                ),
            )
            appendLine()
            appendLine("【群聊共享待归并回合】")
            appendLine(renderPendingTurnsForPlanner(sharedPending))
            append("continuity 只能根据以上共享待归并回合更新；角色私有情绪、关系与互动状态仍只根据当前角色自己的本轮对话更新。")
        }
        return try {
            val plannerReply = completeWithRetry(
                key = key,
                snapshot = snapshot,
                messages = listOf(
                    buildJsonObject {
                        put("role", "system")
                        put("content", prompt)
                    },
                ),
                step = step,
                toolsOverride = JsonArray(emptyList()),
                publishPreview = false,
                maxAttemptsOverride = 1,
            )
            usageTracker.record(snapshot.model, plannerReply.usage)
            chatTurnCoordinator.parsePostTurn(
                plannerReply.content.orEmpty(),
                previous = plannerState,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
            )?.state
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            eventLog.append("group/post-turn", buildJsonObject {
                put("gallery_id", member.galleryId)
                put("status", "failed")
                put("detail", error.message.orEmpty().take(1_000))
            })
            null
        }
    }

    private suspend fun refreshGroupMemberStates(
        replies: List<GroupReplyForStateUpdate>,
        userMessage: String,
        sharedPending: List<ChatPendingTurn>,
    ): GroupStateRefreshBatch {
        if (replies.isEmpty()) return GroupStateRefreshBatch(emptyMap(), complete = true)
        if (replies.size == 1) {
            val reply = replies.single()
            val refreshed = refreshGroupMemberState(
                member = reply.member,
                persona = reply.persona,
                userMessage = userMessage,
                assistantMessage = reply.content,
                sharedPending = sharedPending,
                step = CHAT_POST_TURN_MODEL_STEP + 100,
            )
            return GroupStateRefreshBatch(
                states = mapOf(reply.member.galleryId to (refreshed ?: reply.member.chatState)),
                complete = refreshed != null,
            )
        }

        val snapshot = _state.value
        val key = apiKeys.get() ?: return GroupStateRefreshBatch(
            states = replies.associate { it.member.galleryId to it.member.chatState },
            complete = false,
        )
        val prompt = buildString {
            appendLine("你要一次整理多个群聊角色各自的隐藏状态。每个角色的私有状态完全隔离，禁止把甲角色的判断、关系或经历写进乙角色。")
            appendLine("【群聊共享待归并回合】")
            appendLine(renderPendingTurnsForPlanner(sharedPending))
            appendLine("每个角色 plan 的 continuity 只能根据以上共享待归并回合更新；私有情绪、关系与互动状态只根据该角色自己的本轮对话更新。")
            appendLine("最终只输出一个 JSON 对象，格式为：")
            appendLine("""{"plans":[{"galleryId":"人物ID","plan":{"state":{},"suggestions":[],"turnSignificance":"NONE|MINOR|MAJOR"}}]}""")
            appendLine("每个 plan 必须分别遵循对应角色下面的状态更新规则；suggestions 固定输出空数组，禁止附加解释。")
            replies.forEach { reply ->
                val memberPrompt = chatTurnCoordinator.postTurnPrompt(
                    persona = reply.persona,
                    state = reply.member.chatState.withContextForPlanner(
                        snapshot.groupChat.context.withLegacyFallback(reply.member.chatState),
                    ),
                    userMessage = userMessage,
                    assistantMessage = reply.content,
                ).replace(
                    "不要继续扮演角色，不要解释过程，不要使用 Markdown，只输出一个 JSON 对象。",
                    "不要继续扮演角色，不要解释过程。",
                )
                appendLine()
                appendLine("===== 人物 ${reply.member.galleryId} / ${reply.persona.name} =====")
                appendLine(memberPrompt)
            }
        }

        return try {
            val plannerReply = completeWithRetry(
                key = key,
                snapshot = snapshot,
                messages = listOf(
                    buildJsonObject {
                        put("role", "system")
                        put("content", prompt)
                    },
                ),
                step = CHAT_POST_TURN_MODEL_STEP + 100,
                toolsOverride = JsonArray(emptyList()),
                publishPreview = false,
                maxAttemptsOverride = 1,
            )
            usageTracker.record(snapshot.model, plannerReply.usage)
            val root = json.parseToJsonElement(plannerReply.content.orEmpty()).jsonObject
            val plans = root["plans"]?.jsonArray.orEmpty()
            val result = linkedMapOf<String, ChatCharacterState>()
            plans.forEach { element ->
                val item = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                val galleryId = item["galleryId"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val source = replies.firstOrNull { it.member.galleryId == galleryId } ?: return@forEach
                val plan = item["plan"] ?: return@forEach
                val parsed = chatTurnCoordinator.parsePostTurn(
                    text = plan.toString(),
                    previous = source.member.chatState.withContextForPlanner(
                        snapshot.groupChat.context.withLegacyFallback(source.member.chatState),
                    ),
                    userMessage = userMessage,
                    assistantMessage = source.content,
                ) ?: return@forEach
                result[galleryId] = parsed.state
            }
            val complete = replies.all { reply -> reply.member.galleryId in result }
            replies.forEach { reply ->
                if (reply.member.galleryId !in result) {
                    result[reply.member.galleryId] = reply.member.chatState
                }
            }
            GroupStateRefreshBatch(result, complete)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            eventLog.append("group/post-turn-batch", buildJsonObject {
                put("status", "failed")
                put("detail", error.message.orEmpty().take(1_000))
                put("count", replies.size)
            })
            GroupStateRefreshBatch(
                states = replies.associate { reply ->
                    reply.member.galleryId to reply.member.chatState
                },
                complete = false,
            )
        }
    }

    private suspend fun runGroupChatTurn(
        input: String,
        sourceMessageId: String? = null,
    ) {
        _state.update {
            it.copy(
                running = true,
                error = null,
                replySuggestions = emptyList(),
                groupActiveSpeakerName = null,
                deviceApprovalLease = false,
                pendingApproval = null,
                pendingQuestion = null,
            )
        }
        try {
            val snapshot = _state.value
            require(snapshot.groupChat.members.size >= MIN_GROUP_CHAT_MEMBERS) {
                "群聊至少需要添加 $MIN_GROUP_CHAT_MEMBERS 个角色"
            }
            ensureSystemMessage()
            captureAutoMemoryDirective(input, sourceMessageId)

            val key = apiKeys.get() ?: error("请先配置 DeepSeek API 密钥")
            val members = snapshot.groupChat.members
            val cursor = snapshot.groupChat.turnCursor % members.size
            val rotated = members.drop(cursor) + members.take(cursor)
            val responders = groupChatResponders(input, rotated)
            require(responders.isNotEmpty()) { "群聊里还没有可发言的角色" }
            val groupPromptTokens = responders.maxOfOrNull { member ->
                val persona = member.persona.takeUnless {
                    it.id == PersonaProfile.DEFAULT_PERSONA_ID &&
                        member.personaId != PersonaProfile.DEFAULT_PERSONA_ID
                } ?: chatPersonaStore.get(member.personaId)
                estimateModelTokens(
                    chatReplyCoordinator.buildGroupPrompt(
                        persona = persona,
                        member = member,
                        input = input,
                        allMembers = members,
                        handoffSummary = snapshot.handoffSummary,
                        sharedContext = snapshot.groupChat.context,
                        announcement = snapshot.groupChat.announcement,
                        mayStaySilent = false,
                        silentToken = GROUP_CHAT_SILENT_TOKEN,
                    ),
                )
            } ?: 0
            compactHistoryIfNeeded(extraTokens = groupPromptTokens)

            eventLog.append("turn/start", buildJsonObject {
                put("model", snapshot.model)
                put("mode", "group-chat")
                put("member_count", members.size)
                put("responder_count", responders.size)
            })

            var currentGroup = snapshot.groupChat
            var deliveredReplies = 0
            val repliesForStateUpdate = mutableListOf<GroupReplyForStateUpdate>()
            val baseHistory = modelHistory.snapshot()

            coroutineScope {
                val generatedReplies = responders.mapIndexed { index, initialMember ->
                    val member = currentGroup.members.firstOrNull {
                        it.galleryId == initialMember.galleryId
                    } ?: initialMember
                    async {
                        generateGroupReply(
                            key = key,
                            snapshot = snapshot,
                            baseHistory = baseHistory,
                            input = input,
                            allMembers = members,
                            member = member,
                            index = index,
                        )
                    }
                }

                generatedReplies.forEachIndexed { index, deferred ->
                    val initialMember = responders[index]
                    _state.update { current ->
                        current.copy(groupActiveSpeakerName = initialMember.displayName)
                    }

                    val generated = deferred.await()
                    generated.failure?.let { failure ->
                        eventLog.append("group/agent-failed", buildJsonObject {
                            put("gallery_id", generated.member.galleryId)
                            put("persona_id", generated.member.personaId)
                            put("detail", failure.message.orEmpty().take(1_000))
                        })
                        if (responders.size == 1) {
                            throw (failure as? Exception
                                ?: IllegalStateException(failure.message ?: "群聊角色回复失败", failure))
                        }
                        return@forEachIndexed
                    }

                    val content = generated.content
                    if (content.isBlank() || content == GROUP_CHAT_SILENT_TOKEN) {
                        eventLog.append("group/agent-empty", buildJsonObject {
                            put("gallery_id", generated.member.galleryId)
                            put("persona_id", generated.member.personaId)
                        })
                        return@forEachIndexed
                    }

                    val transcript = transcriptRuntime.newMessage(
                        role = "assistant",
                        content = content,
                        speakerId = generated.member.galleryId,
                        speakerName = generated.persona.name,
                    )
                    val eventData = transcriptRuntime.withTranscript(
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", content)
                            put("speaker_id", generated.member.galleryId)
                            put("speaker_name", generated.persona.name)
                        },
                        listOf(transcript),
                    )
                    val assistantEvent = eventLog.append("assistant/message", eventData)
                    modelHistory.append(
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", groupTranscriptLine(transcript))
                        },
                    )
                    updateContextMetrics()
                    val beforeAssistant = _state.value
                    transcriptRuntime.applyMessages(
                        listOf(transcript),
                        assistantEvent.sequence,
                        clearStreamingPreview = true,
                    )
                    val pendingContext = currentGroup.context
                        .applySceneTurn(
                            userMessage = input,
                            assistantMessage = content,
                            sequence = assistantEvent.sequence,
                        )
                        .enqueuePending(
                        ChatPendingTurn(
                            sequence = assistantEvent.sequence,
                            userMessageId = beforeAssistant.transcriptIndex.latestUserMessageId.orEmpty(),
                            assistantMessageId = transcript.id,
                            branchHeadId = transcript.id,
                            userMessage = input,
                            assistantMessage = content,
                            generation = currentGroup.context.generation,
                        ),
                    )
                    currentGroup = currentGroup.copy(context = pendingContext)
                    _state.update { current ->
                        if (current.sessionId == snapshot.sessionId) {
                            current.copy(groupChat = currentGroup)
                        } else {
                            current
                        }
                    }
                    if (
                        beforeAssistant.chatBranches.nodes.isNotEmpty() &&
                        beforeAssistant.transcriptIndex.branchingEligible
                    ) {
                        _state.update { current ->
                            current.copy(
                                chatBranches = appendMaterializedChatBranchMessage(
                                    current = current.chatBranches,
                                    activeMessages = beforeAssistant.messages,
                                    message = transcript,
                                    parentId = beforeAssistant.transcriptIndex.latestDialogueMessageId,
                                    chatState = current.chatState,
                                    chatContext = current.groupChat.context,
                                    replySuggestions = emptyList(),
                                ),
                            )
                        }
                    }
                    deliveredReplies += 1
                    repliesForStateUpdate += GroupReplyForStateUpdate(
                        member = generated.member,
                        persona = generated.persona,
                        content = content,
                    )
                }
            }

            require(deliveredReplies > 0) { "群聊角色这一轮都没有给出可用回复" }

            val sharedPendingForRefresh = currentGroup.context.pendingTurns
                .asSequence()
                .filter { it.sequence > currentGroup.context.processedThroughSequence }
                .filter { it.generation == currentGroup.context.generation }
                .sortedBy(ChatPendingTurn::sequence)
                .take(GROUP_POST_TURN_PENDING_BATCH)
                .toList()
            val refreshBatch = refreshGroupMemberStates(
                replies = repliesForStateUpdate,
                userMessage = input,
                sharedPending = sharedPendingForRefresh,
            )
            val refreshedStates = refreshBatch.states
            val refreshedInReplyOrder = repliesForStateUpdate
                .mapNotNull { reply -> refreshedStates[reply.member.galleryId] }
            val nextSharedContext = finalizeGroupContextAfterRefresh(
                context = currentGroup.context,
                statesInReplyOrder = refreshedInReplyOrder,
                processedPending = sharedPendingForRefresh,
                complete = refreshBatch.complete,
            )
            if (!refreshBatch.complete) {
                eventLog.append("group/post-turn-batch", buildJsonObject {
                    put("status", "pending-preserved")
                    put("pending_count", currentGroup.context.pendingTurns.size)
                })
            }
            currentGroup = currentGroup.copy(
                context = nextSharedContext,
                members = currentGroup.members.map { existing ->
                    val nextState = refreshedStates[existing.galleryId] ?: return@map existing
                    val source = repliesForStateUpdate.firstOrNull { it.member.galleryId == existing.galleryId }
                    existing.copy(
                        displayName = source?.persona?.name ?: existing.displayName,
                        chatState = nextState.copy(
                            scene = ChatSceneState(),
                            continuity = ChatContinuityState(),
                        ),
                    )
                },
            )
            repliesForStateUpdate.forEach { reply ->
                val nextState = refreshedStates[reply.member.galleryId] ?: return@forEach
                val privateState = nextState.copy(
                    scene = ChatSceneState(),
                    continuity = ChatContinuityState(),
                )
                runCatching {
                    chatPersonaGalleryStore.updateGroupChatState(reply.member.galleryId, privateState)
                }.onFailure { error ->
                    eventLog.append("group/state-persist", buildJsonObject {
                        put("gallery_id", reply.member.galleryId)
                        put("status", "failed")
                        put("detail", error.message.orEmpty().take(1_000))
                    })
                }
            }

            val nextCursor = (snapshot.groupChat.turnCursor + 1) % members.size
            currentGroup = currentGroup.copy(turnCursor = nextCursor)
            _state.update { current ->
                if (current.sessionId == snapshot.sessionId) {
                    current.copy(
                        groupChat = currentGroup,
                        groupActiveSpeakerName = null,
                        replySuggestions = emptyList(),
                    )
                } else {
                    current
                }
            }

            eventLog.append("turn/end", buildJsonObject {
                put("reason", "completed")
                put("mode", "group-chat")
                put("replies", deliveredReplies)
            })
            if (hasChatBranchAlternatives(_state.value.chatBranches)) {
                persistChatBranchState("group/branch-completed")
            }
            checkpointModelHistory("group/completed")
            persist()
        } catch (cancelled: CancellationException) {
            eventLog.append("turn/end", buildJsonObject {
                put("reason", "aborted")
                put("mode", "group-chat")
            })
            checkpointModelHistory("group/cancelled")
            persist()
            throw cancelled
        } catch (error: Exception) {
            val detail = error.message ?: "群聊请求失败"
            _state.update { it.copy(error = detail, groupActiveSpeakerName = null) }
            eventLog.append("turn/end", buildJsonObject {
                put("reason", "error")
                put("mode", "group-chat")
                put("detail", detail.take(2_000))
            })
            checkpointModelHistory("group/failed")
            persist()
        } finally {
            _state.update {
                it.copy(
                    running = false,
                    groupActiveSpeakerName = null,
                    pendingApproval = null,
                    pendingQuestion = null,
                    deviceApprovalLease = false,
                )
            }
            persist()
            val completedJob = currentCoroutineContext()[Job]
            synchronized(runStateLock) {
                if (activeJob === completedJob) activeJob = null
            }
            startNextQueuedTurnIfIdle()?.start()
        }
    }

    private suspend fun runChatTurn(
        input: String,
        replacingMessageId: String? = null,
        sourceMessageId: String? = null,
    ) {
        cancelChatPostTurn()
        _state.update {
            it.copy(
                running = true,
                error = null,
                deviceApprovalLease = false,
                pendingApproval = null,
                pendingQuestion = null,
            )
        }
        try {
            ensureSystemMessage()
            val snapshot = _state.value
            val branchEligible = snapshot.transcriptIndex.branchingEligible
            val branchParentId = snapshot.transcriptIndex.latestUserMessageId
            val branchBase = snapshot.chatBranches
            captureAutoMemoryDirective(input, sourceMessageId ?: snapshot.transcriptIndex.latestUserMessageId)
            val relationshipMemory = chatRelationshipMemoryContext(input, snapshot)
            val preparedChat = chatTurnCoordinator.prepare(
                snapshot = snapshot,
                input = input,
                relationshipMemory = relationshipMemory,
            )
            val chatContext = preparedChat.context
            val dynamicContext = preparedChat.dynamicContext
            compactHistoryIfNeeded(
                extraTokens = estimateModelTokens(chatContext.stablePrompt) +
                    estimateModelTokens(dynamicContext),
            )
            val key = apiKeys.get() ?: error("请先配置 DeepSeek API 密钥")
            val requestMessages = prepareLocalMultimodalMessages(
                messages = withChatTurnContext(
                    history = boundedChatRequestHistory(
                        if (replacingMessageId == null) modelHistory.snapshot() else modelHistory.dropLast(1),
                        recentMessages = CHAT_RECENT_HISTORY_MESSAGES,
                        currentFacts = snapshot.chatContext
                            .withLegacyFallback(snapshot.chatState)
                            .canonicalFactLines(),
                    ),
                    stableContext = chatContext.stablePrompt,
                    dynamicContext = dynamicContext,
                ),
                workspaceRoot = File(workspace.path),
                mode = LocalImageInputMode.NATIVE,
                budget = imageRequestBudget,
            )

            eventLog.append("turn/start", buildJsonObject {
                put("model", snapshot.model)
                put("mode", "chat")
                put("persona_id", chatContext.persona.id)
            })

            val rawReply = completeWithRetry(
                key = key,
                snapshot = snapshot,
                messages = requestMessages,
                step = 1,
                toolsOverride = JsonArray(emptyList()),
                // Chat candidates must pass style/repetition/scene guards before anything is shown.
                publishPreview = false,
                streamFilterPhrases = chatStreamFilterPhrases(snapshot, chatContext.persona),
                persistOverflowHistory = true,
                temperature = CHAT_ROLEPLAY_TEMPERATURE,
            )

            val reply = chatReplyCoordinator.finalizeDirect(
                snapshot = snapshot,
                reply = rawReply,
                userMessage = input,
                step = 1,
                retryRaw = { repairHint ->
                    completeWithRetry(
                        key = key,
                        snapshot = snapshot,
                        messages = withEphemeralContext(requestMessages, repairHint),
                        step = 1,
                        toolsOverride = JsonArray(emptyList()),
                        publishPreview = false,
                        maxAttemptsOverride = 1,
                        allowContextOverflowRecovery = false,
                        temperature = CHAT_ROLEPLAY_TEMPERATURE,
                    )
                },
                appendEvent = { type, data ->
                    eventLog.append(type, data)
                },
            )

            if (replacingMessageId != null && reply.content.isNullOrBlank()) {
                error("模型没有返回可用回复")
            }

            val transcriptMessages = buildList {
                reply.content?.takeIf(String::isNotBlank)?.let { content ->
                    add(transcriptRuntime.newMessage("assistant", content))
                }
            }
            val assistantData = transcriptRuntime.withTranscript(reply.message, transcriptMessages)
            val assistantEvent = eventLog.append(
                "assistant/message",
                if (replacingMessageId == null) assistantData else JsonObject(
                    assistantData + ("replaces" to JsonPrimitive(replacingMessageId)),
                ),
            )
            if (replacingMessageId != null) {
                modelHistory.reset(modelHistory.dropLast(1))
                _state.update { state ->
                    val retained = state.messages.filterNot { it.id == replacingMessageId }
                    state.copy(
                        messages = retained,
                        transcriptIndex = state.transcriptIndex.copy(
                            totalMessageCount = (state.transcriptIndex.totalMessageCount - 1L)
                                .coerceAtLeast(0L),
                        ),
                    )
                }
            }
            modelHistory.append(reply.message)
            updateContextMetrics()
            transcriptRuntime.applyMessages(
                transcriptMessages,
                assistantEvent.sequence,
                clearStreamingPreview = true,
            )
            val assistantTranscript = transcriptMessages.lastOrNull()
            if (
                branchEligible &&
                branchBase.nodes.isNotEmpty() &&
                assistantTranscript != null &&
                branchParentId != null
            ) {
                val branches = upsertChatBranchNode(
                    branchBase,
                    LocalChatBranchNode(
                        message = assistantTranscript,
                        parentId = branchParentId,
                        chatStateAfter = snapshot.chatState,
                    ),
                    select = true,
                )
                _state.update { it.copy(chatBranches = branches) }
                if (hasChatBranchAlternatives(branches)) {
                    persistChatBranchState(
                        if (replacingMessageId != null) "assistant-regenerated" else "assistant-branch-completed",
                    )
                }
            }
            eventLog.append("turn/end", buildJsonObject {
                put("reason", "completed")
                put("steps", 1)
                put("messages", _state.value.transcriptIndex.totalMessageCount)
                put("mode", "chat")
            })
            if (replacingMessageId != null) checkpointModelHistory("chat/regenerated")
            else checkpointModelHistoryAtTurnBoundary("chat/completed")

            _state.update {
                it.copy(
                    running = false,
                    pendingApproval = null,
                    pendingQuestion = null,
                    deviceApprovalLease = false,
                )
            }
            persist()

            val assistantMessage = reply.content?.takeIf(String::isNotBlank)
            if (assistantTranscript != null && assistantMessage != null) {
                scheduleChatPostTurn(
                    userMessage = input,
                    assistantMessage = assistantMessage,
                    persona = chatContext.persona,
                    expectedSessionId = snapshot.sessionId,
                    expectedAssistantMessageId = assistantTranscript.id,
                    expectedBaseState = _state.value.chatState,
                    sourceUserMessageId = sourceMessageId
                        ?: snapshot.transcriptIndex.latestUserMessageId,
                )
            }
        } catch (cancelled: CancellationException) {
            eventLog.append("turn/end", buildJsonObject {
                put("reason", "aborted")
                put("mode", "chat")
            })
            checkpointModelHistoryAtTurnBoundary("chat/cancelled")
            persist()
            throw cancelled
        } catch (error: Exception) {
            if (replacingMessageId != null) {
                val oldNode = _state.value.chatBranches.nodes.firstOrNull {
                    it.message.id == replacingMessageId
                }
                oldNode?.chatStateAfter?.let { restoredState ->
                    _state.update { current ->
                        current.copy(
                            chatState = restoredState,
                            chatContext = restoreBranchContext(
                                snapshot = oldNode.chatContextAfter,
                                legacyState = oldNode.chatStateAfter,
                                previousGeneration = current.chatContext.generation,
                            ),
                            replySuggestions = oldNode.replySuggestionsAfter,
                        )
                    }
                }
            }
            val detail = error.message ?: "聊天请求失败"
            _state.update { it.copy(error = detail) }
            eventLog.append("turn/end", buildJsonObject {
                put("reason", "error")
                put("detail", detail.take(2_000))
                put("mode", "chat")
            })
            checkpointModelHistoryAtTurnBoundary("chat/failed")
            persist()
        } finally {
            _state.update {
                it.copy(
                    running = false,
                    pendingApproval = null,
                    pendingQuestion = null,
                    deviceApprovalLease = false,
                )
            }
            persist()
            val completedJob = currentCoroutineContext()[Job]
            synchronized(runStateLock) {
                if (activeJob === completedJob) activeJob = null
            }
            startNextQueuedTurnIfIdle()?.start()
        }
    }

    private suspend fun runAgentTurn(
        input: String,
        memoryInput: String = input,
        sourceMessageId: String? = null,
    ) {
        val runPolicy = localAgentRunPolicy(_state.value.usageMode)
        toolExecutionCoordinator.clearTurnCapabilities()
        if (_state.value.usageMode == LocalUsageMode.WORK && runCatching { githubCredentials.configured() }.getOrDefault(false))
            toolExecutionCoordinator.enableGitHubConnectorTools()
        if (_state.value.usageMode == LocalUsageMode.CHAT) {
            // Queued chat turns can start immediately after the previous answer. Stop that
            // answer's background relationship/state refresh before capturing this turn's context.
            cancelChatPostTurn()
        }
        val foregroundSessionId = currentSessionId
        var foregroundOutcome = LocalExecutionService.OUTCOME_COMPLETED
        LocalExecutionService.holdTurn(context, foregroundSessionId)
        _state.update { it.copy(running = true, error = null, deviceApprovalLease = false, workflowProgress = null) }
        val repliesByStep = mutableMapOf<Int, LocalModelReply>()
        var finalChatAssistant: LocalHarnessMessage? = null
        var modelStep = 0
        var requestPrepared = false
        var ephemeralContext = ""
        var chatStableContext = ""
        var chatDynamicContext = ""
        val mainMaxSteps = _state.value.mainMaxSteps
        val runSnapshot = _state.value
        val runContext = agentRunCoordinator.start(
            sessionId = foregroundSessionId,
            usageMode = runSnapshot.usageMode,
            model = runSnapshot.model,
            baseUrl = runSnapshot.baseUrl,
            planMode = runSnapshot.planMode,
            policy = runPolicy,
            safeAutoApprovalEnabled = runSnapshot.safeAutoApprovalEnabled,
            maxSteps = if (runPolicy.allowToolExecution) mainMaxSteps else 1,
            input = input,
            memoryInput = memoryInput,
            allowMutation = runPolicy.allowToolExecution,
            resourceBudget = LocalAgentRunResourceBudget(
                maxModelRequests = resourceScheduler.budget.maxModelRequests,
                maxAgents = resourceScheduler.budget.maxAgents,
                maxTerminals = resourceScheduler.budget.maxTerminals,
                maxVirtualDisplays = resourceScheduler.budget.maxVirtualDisplays,
                maxLanguageServers = resourceScheduler.budget.maxLanguageServers,
            ),
            toolNames = toolExecutionCoordinator.visibleToolNames(runPolicy),
            contextChars = runSnapshot.contextChars,
        )
        var activeStep: Int? = null
        var activeToolCalls = emptyList<AgentToolCall>()
        val startedToolCallIds = linkedSetOf<String>()
        val completedToolCallIds = linkedSetOf<String>()

        fun settlePendingTools(reason: String) {
            val settlements = pendingToolSettlements(
                calls = activeToolCalls,
                startedCallIds = startedToolCallIds,
                completedCallIds = completedToolCallIds,
            )
            if (settlements.isEmpty()) return
            settlements.forEach { settlement ->
                val result = SessionRecovery.interruptedToolResult(
                    callId = settlement.call.id,
                    name = settlement.call.name,
                    step = activeStep,
                    started = settlement.started,
                )
                eventLog.append("tool/result", buildJsonObject {
                    result.step?.let { put("step", it) }
                    put("id", result.callId)
                    result.name?.let { put("name", it) }
                    put("content", result.content)
                    put("model_content", result.modelContent)
                    put("is_error", true)
                    put("error_code", result.code)
                    put("retryable", result.code == SessionRecovery.TOOL_NOT_STARTED)
                    put(
                        "side_effect",
                        if (result.code == SessionRecovery.TOOL_OUTCOME_UNKNOWN) "possible" else "none",
                    )
                    put("runtime_settlement", true)
                    put("reason", reason)
                })
                modelHistory.append(
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", result.callId)
                        put("content", result.modelContent)
                    },
                )
                completedToolCallIds += result.callId
            }
        }

        val loop = AgentLoop(
            model = AgentModel {
                // Persistent history stays compact; user rules, recalled memory and handoff are
                // assembled per request and are deliberately never written back into modelHistory.
                if (!requestPrepared) {
                    ensureSystemMessage()
                    val snapshot = _state.value
                    if (snapshot.usageMode == LocalUsageMode.CHAT) {
                        captureAutoMemoryDirective(memoryInput, sourceMessageId)
                        val relationshipMemory = chatRelationshipMemoryContext(memoryInput, snapshot)
                        val preparedChat = chatTurnCoordinator.prepare(
                            snapshot = snapshot,
                            input = input,
                            relationshipMemory = relationshipMemory,
                        )
                        chatStableContext = preparedChat.context.stablePrompt
                        chatDynamicContext = preparedChat.dynamicContext
                    } else {
                        ephemeralContext = contextComposer.compose(
                            ContextRequest(
                                query = input,
                                mode = snapshot.conversationMode,
                                projectId = snapshot.projectId,
                                lineageId = snapshot.lineageId,
                                handoffSummary = snapshot.handoffSummary,
                            ),
                        )
                        captureAutoMemoryDirective(memoryInput, sourceMessageId)
                    }
                    requestPrepared = true
                }
                drainPendingInputsIntoHistory()
                val key = apiKeys.get() ?: error("请先配置 DeepSeek API 密钥")
                val snapshot = _state.value
                val tools = modelToolSchemas(runPolicy)
                // Re-check before every model step. Tool results and queued user messages can grow
                // substantially inside one turn, so checking only at turn start is insufficient.
                val productContextTokens = if (snapshot.usageMode == LocalUsageMode.CHAT) {
                    estimateModelTokens(chatStableContext) + estimateModelTokens(chatDynamicContext)
                } else {
                    estimateModelTokens(ephemeralContext)
                }
                compactHistoryIfNeeded(
                    extraTokens = productContextTokens + estimateModelTokens(tools.toString()),
                )
                val durableRequestMessages = if (snapshot.usageMode == LocalUsageMode.CHAT) {
                    withChatTurnContext(
                        history = boundedChatRequestHistory(
                            modelHistory.snapshot(),
                            recentMessages = CHAT_RECENT_HISTORY_MESSAGES,
                            currentFacts = snapshot.chatContext
                                .withLegacyFallback(snapshot.chatState)
                                .canonicalFactLines(),
                        ),
                        stableContext = chatStableContext,
                        dynamicContext = chatDynamicContext,
                    )
                } else {
                    withEphemeralContext(modelHistory.snapshot(), ephemeralContext)
                }
                val selectedMode = resolveLocalImageInputMode(
                    snapshot.imageInputMode,
                    imageCapabilities,
                    snapshot.baseUrl,
                    snapshot.model,
                )
                if (hasLocalImageRefs(durableRequestMessages) && selectedMode == LocalImageInputMode.TOOL) {
                    throw IllegalStateException("当前模型不支持图片理解，请切换支持图片的模型后重试。")
                }
                val requestMessages = prepareLocalMultimodalMessages(
                    messages = durableRequestMessages,
                    workspaceRoot = File(workspace.path),
                    mode = selectedMode,
                    budget = imageRequestBudget,
                )
                val nativeImagesSent = hasMaterializedImageUrls(requestMessages)
                val rawReply = try {
                    completeWithRetry(
                        key = key,
                        snapshot = snapshot,
                        messages = requestMessages,
                        step = modelStep + 1,
                        toolsOverride = tools,
                        publishPreview = snapshot.usageMode != LocalUsageMode.CHAT,
                        streamFilterPhrases = chatStreamFilterPhrases(snapshot),
                        persistOverflowHistory = true,
                        temperature = CHAT_ROLEPLAY_TEMPERATURE.takeIf {
                            snapshot.usageMode == LocalUsageMode.CHAT
                        },
                    ).also {
                        if (nativeImagesSent) {
                            imageCapabilities.markSupported(snapshot.baseUrl, snapshot.model)
                        }
                    }
                } catch (error: Throwable) {
                    val nativeImageRejected =
                        nativeImagesSent &&
                            imageInputUnsupported(error)
                    if (nativeImageRejected) {
                        imageCapabilities.markUnsupported(snapshot.baseUrl, snapshot.model)
                    }
                    if (nativeImageRejected) {
                        throw IllegalStateException(
                            "当前模型不支持图片理解，请切换支持图片的模型后重试。",
                            error,
                        )
                    } else {
                        throw error
                    }
                }
                val reply = enforceChatStyle(
                    key = key,
                    snapshot = snapshot,
                    messages = requestMessages,
                    step = modelStep + 1,
                    reply = rawReply,
                    userMessage = memoryInput,
                )
                val effectiveReply = if (!runPolicy.allowToolExecution && reply.toolCalls.isNotEmpty()) {
                    eventLog.append("chat/tool-call-blocked", buildJsonObject {
                        put("count", reply.toolCalls.size)
                        put("reason", "chat-capability-policy")
                    })
                    val content = reply.content?.takeIf(String::isNotBlank)
                        ?: throw IllegalStateException("模型未返回可显示的聊天内容，请重试。")
                    reply.copy(
                        message = buildJsonObject {
                            put("role", "assistant")
                            put("content", content)
                        },
                        toolCalls = emptyList(),
                    )
                } else {
                    reply
                }
                modelStep += 1
                repliesByStep[modelStep] = effectiveReply
                AgentModelReply(
                    content = effectiveReply.content.orEmpty(),
                    toolCalls = if (runPolicy.allowToolExecution) {
                        effectiveReply.toolCalls.map { call ->
                            AgentToolCall(
                                id = call.id,
                                name = call.name,
                                arguments = call.arguments,
                                rawArguments = call.rawArguments,
                            )
                        }
                    } else {
                        emptyList()
                    },
                )
            },
            tools = AgentToolExecutor { call ->
                if (!runPolicy.allowToolExecution) {
                    AgentToolResult(
                        content = "聊天模式不提供工具执行能力。",
                        isError = true,
                        errorCode = "TOOLS_DISABLED",
                    )
                } else {
                    executeSafely(call.toLocalToolCall(), allowMutation = true)
                }
            },
            toolBatch = AgentToolBatchExecutor { calls ->
                if (!runPolicy.allowToolExecution) {
                    calls.map {
                        AgentToolResult(
                            content = "聊天模式不提供工具执行能力。",
                            isError = true,
                            errorCode = "TOOLS_DISABLED",
                        )
                    }
                } else {
                    executeToolBatch(
                        calls = calls.map { it.toLocalToolCall() },
                        allowMutation = true,
                    ).map { (_, result) -> result }
                }
            },
            isParallelTool = { call ->
                runPolicy.allowToolExecution && call.name in PARALLEL_SUBAGENT_TOOLS
            },
            eventSink = AgentEventSink { event ->
                when (event) {
                    is AgentEvent.TurnStarted -> {
                        eventLog.append("turn/start", buildJsonObject {
                            put("model", _state.value.model)
                        })
                    }
                    is AgentEvent.StepStarted -> {
                        activeStep = event.step
                        LocalExecutionService.holdTurn(context, foregroundSessionId, event.step)
                        activeToolCalls = emptyList()
                        startedToolCallIds.clear()
                        completedToolCallIds.clear()
                        eventLog.append("step/start", buildJsonObject {
                            put("step", event.step)
                        })
                    }
                    is AgentEvent.AssistantObserved -> {
                        val reply = repliesByStep.remove(event.step)
                            ?: error("缺少第 ${event.step} 步模型响应")
                        val beforeAssistant = _state.value
                        val transcriptMessages = buildList {
                            reply.reasoning?.takeIf {
                                beforeAssistant.usageMode == LocalUsageMode.WORK && it.isNotBlank()
                            }?.let { reasoning ->
                                add(transcriptRuntime.newMessage("reasoning", reasoning))
                            }
                            reply.content?.takeIf(String::isNotBlank)?.let { content ->
                                add(
                                    transcriptRuntime.newMessage(
                                        role = if (event.toolCalls.isEmpty()) "assistant" else "progress",
                                        content = content,
                                    ),
                                )
                            }
                        }
                        activeToolCalls = event.toolCalls
                        startedToolCallIds.clear()
                        completedToolCallIds.clear()
                        val assistantEvent = eventLog.append(
                            "assistant/message",
                            transcriptRuntime.withTranscript(reply.message, transcriptMessages),
                        )
                        if (beforeAssistant.usageMode == LocalUsageMode.CHAT && event.toolCalls.isEmpty()) {
                            finalChatAssistant = transcriptMessages.lastOrNull { message ->
                                message.role == "assistant" && message.content.isNotBlank()
                            }
                        }
                        if (
                            beforeAssistant.usageMode == LocalUsageMode.CHAT &&
                            !beforeAssistant.groupChat.enabled &&
                            event.toolCalls.isEmpty()
                        ) {
                            val assistantTranscript = transcriptMessages.lastOrNull { message ->
                                message.role == "assistant" && message.content.isNotBlank()
                            }
                            if (assistantTranscript != null) {
                                val hardContext = beforeAssistant.chatContext
                                    .withLegacyFallback(beforeAssistant.chatState)
                                    .applySceneTurn(
                                        userMessage = memoryInput,
                                        assistantMessage = assistantTranscript.content,
                                        sequence = assistantEvent.sequence,
                                    )
                                _state.update { current ->
                                    if (current.sessionId == beforeAssistant.sessionId) {
                                        current.copy(chatContext = hardContext)
                                    } else {
                                        current
                                    }
                                }
                            }
                        }
                        modelHistory.append(reply.message)
                        updateContextMetrics()
                        transcriptRuntime.applyMessages(transcriptMessages, assistantEvent.sequence, clearStreamingPreview = true)
                        if (
                            beforeAssistant.usageMode == LocalUsageMode.CHAT &&
                            !beforeAssistant.groupChat.enabled &&
                            event.toolCalls.isEmpty() &&
                            beforeAssistant.chatBranches.nodes.isNotEmpty() &&
                            beforeAssistant.transcriptIndex.branchingEligible
                        ) {
                            val assistantTranscript = transcriptMessages.lastOrNull { message ->
                                message.role == "assistant"
                            }
                            if (assistantTranscript != null) {
                                val branches = appendMaterializedChatBranchMessage(
                                    current = beforeAssistant.chatBranches,
                                    activeMessages = beforeAssistant.messages,
                                    message = assistantTranscript,
                                    parentId = beforeAssistant.transcriptIndex.latestUserMessageId,
                                    chatState = beforeAssistant.chatState,
                                    chatContext = _state.value.chatContext,
                                    replySuggestions = beforeAssistant.replySuggestions,
                                )
                                _state.update { current -> current.copy(chatBranches = branches) }
                                if (hasChatBranchAlternatives(branches)) {
                                    persistChatBranchState("assistant-branch-completed")
                                }
                            }
                        }
                        persist()
                    }
                    is AgentEvent.ToolStarted -> {
                        startedToolCallIds += event.call.id
                        eventLog.append("tool/call", buildJsonObject {
                            put("step", event.step)
                            put("id", event.call.id)
                            put("name", event.call.name)
                            put("arguments", event.call.arguments)
                        })
                    }
                    is AgentEvent.ToolFinished -> {
                        val boundedContent = retainToolResult(
                            sessionId = currentSessionId,
                            callId = event.call.id,
                            result = event.output,
                        )
                        val modelOutput = AgentToolResult(
                            content = boundedContent,
                            isError = event.isError,
                            errorCode = event.errorCode,
                            retryable = event.retryable,
                            sideEffect = event.sideEffect,
                            recoveryHint = event.recoveryHint,
                        ).modelVisibleContent()
                        val transcriptMessage = transcriptRuntime.newMessage(
                            role = "tool",
                            content = boundedContent,
                            toolName = event.call.name,
                            contentAlreadyBounded = true,
                        )
                        val toolEvent = eventLog.append("tool/result", buildJsonObject {
                            put("step", event.step)
                            put("id", event.call.id)
                            put("name", event.call.name)
                            put(
                                "content",
                                truncateWithoutSplittingSurrogatePair(event.output, MAX_EVENT_CHARS),
                            )
                            put("model_content", modelOutput)
                            put("is_error", event.isError)
                            event.errorCode?.let { put("error_code", it) }
                            put("retryable", event.retryable)
                            put("side_effect", event.sideEffect.name.lowercase())
                            event.recoveryHint?.let { put("recovery_hint", it) }
                            put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                        })
                        modelHistory.append(
                            buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", event.call.id)
                                put("content", modelOutput)
                            },
                        )
                        completedToolCallIds += event.call.id
                        updateContextMetrics()
                        transcriptRuntime.applyMessages(listOf(transcriptMessage), toolEvent.sequence)
                        persist()
                    }
                    is AgentEvent.StepFinished -> {
                        eventLog.append("step/end", buildJsonObject {
                            put("step", event.step)
                        })
                        activeStep = null
                        activeToolCalls = emptyList()
                        startedToolCallIds.clear()
                        completedToolCallIds.clear()
                    }
                    is AgentEvent.TurnCompleted -> {
                        eventLog.append("turn/end", buildJsonObject {
                            put("reason", "completed")
                            put("steps", event.steps)
                            put("messages", _state.value.transcriptIndex.totalMessageCount)
                        })
                        checkpointModelHistoryAtTurnBoundary("turn/completed")
                    }
                    is AgentEvent.TurnStepLimit -> {
                        val transcriptMessage = transcriptRuntime.newMessage(
                            "system",
                            "本轮达到 $mainMaxSteps 步安全上限，请继续发送消息以恢复任务。",
                        )
                        val turnEnd = eventLog.append("turn/end", buildJsonObject {
                            put("reason", "step_limit")
                            put("steps", event.steps)
                            put("messages", _state.value.transcriptIndex.totalMessageCount + 1L)
                            put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                        })
                        transcriptRuntime.applyMessages(listOf(transcriptMessage), turnEnd.sequence)
                        checkpointModelHistoryAtTurnBoundary("turn/step-limit")
                        persist()
                    }
                    is AgentEvent.TurnFailed -> {
                        settlePendingTools("failed")
                        val detail = event.reason.take(2_000)
                        val transcriptMessage = transcriptRuntime.newMessage("system", "执行失败：$detail")
                        val turnEnd = eventLog.append("turn/end", buildJsonObject {
                            put("reason", "error")
                            put("detail", detail)
                            put("messages", _state.value.transcriptIndex.totalMessageCount + 1L)
                            put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                        })
                        transcriptRuntime.applyMessages(listOf(transcriptMessage), turnEnd.sequence)
                        checkpointModelHistoryAtTurnBoundary("turn/failed")
                        persist()
                    }
                    is AgentEvent.TurnCancelled -> {
                        settlePendingTools("cancelled")
                        val transcriptMessage = transcriptRuntime.newMessage("system", "本轮已停止。")
                        val turnEnd = eventLog.append("turn/end", buildJsonObject {
                            put("reason", "aborted")
                            put("messages", _state.value.transcriptIndex.totalMessageCount + 1L)
                            put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                        })
                        transcriptRuntime.applyMessages(listOf(transcriptMessage), turnEnd.sequence)
                        checkpointModelHistoryAtTurnBoundary("turn/cancelled")
                        persist()
                    }
                }
                agentRunCoordinator.recordEvent(runContext, event)
            },
            maxSteps = if (runPolicy.allowToolExecution) mainMaxSteps else 1,
            idFactory = { runContext.runId },
        )

        try {
            loop.run(input)
            if (_state.value.usageMode == LocalUsageMode.CHAT) {
                val postTurnSnapshot = _state.value
                finalChatAssistant?.let { assistantMessage ->
                    scheduleChatPostTurn(
                        userMessage = memoryInput,
                        assistantMessage = assistantMessage.content,
                        persona = postTurnSnapshot.chatPersona,
                        expectedSessionId = postTurnSnapshot.sessionId,
                        expectedAssistantMessageId = assistantMessage.id,
                        expectedBaseState = postTurnSnapshot.chatState,
                        sourceUserMessageId = sourceMessageId
                            ?: runSnapshot.transcriptIndex.latestUserMessageId,
                    )
                }
            }
        } catch (_: CancellationException) {
            foregroundOutcome = LocalExecutionService.OUTCOME_CANCELLED
            // TurnCancelled durably records and projects the visible stop message.
        } catch (error: Exception) {
            foregroundOutcome = LocalExecutionService.OUTCOME_FAILED
            _state.update { it.copy(error = error.message ?: "本机执行失败") }
            // TurnFailed durably records and projects the visible failure message.
        } finally {
            interactions.cancelAll()
            _state.update {
                it.copy(
                    running = false,
                    pendingApproval = null,
                    pendingQuestion = null,
                    deviceApprovalLease = false,
                )
            }
            persist()
            val completedJob = currentCoroutineContext()[Job]
            synchronized(runStateLock) {
                if (activeJob === completedJob) activeJob = null
            }
            LocalExecutionService.releaseTurn(context, foregroundSessionId, foregroundOutcome)
            startNextQueuedTurnIfIdle()?.start()
        }
    }

    private fun AgentToolCall.toLocalToolCall(): LocalToolCall = LocalToolCall(
        id = id,
        name = name,
        arguments = arguments,
        rawArguments = rawArguments,
    )

    private suspend fun executeToolBatch(
        calls: List<LocalToolCall>,
        allowMutation: Boolean,
        binding: LocalWorkRunBinding? = null,
    ): List<Pair<LocalToolCall, AgentToolResult>> {
        val parallelSubagents = calls.size > 1 && calls.all { it.name in PARALLEL_SUBAGENT_TOOLS }
        if (!parallelSubagents) {
            return calls.map { call -> call to executeSafely(call, allowMutation, binding) }
        }
        return isolatedParallelMap(calls) { call ->
            call to executeSafely(call, allowMutation, binding)
        }.mapIndexed { index, result ->
            result.getOrElse { error ->
                val call = calls[index]
                call to toolFailureResult(
                    call,
                    "PARALLEL_TASK_ERROR",
                    error.message ?: error::class.java.simpleName,
                )
            }
        }
    }

    private suspend fun executeSafely(
        call: LocalToolCall,
        allowMutation: Boolean,
        binding: LocalWorkRunBinding? = null,
    ): AgentToolResult = try {
        executeRegistered(call, allowMutation, binding)
    } catch (cancelled: CancellationException) {
        if (!currentCoroutineContext().isActive) throw cancelled
        toolFailureResult(call, "TASK_CANCELLED", cancelled.message ?: "子任务自身被取消；同批其他任务继续运行")
    } catch (error: LocalWebException) {
        toolFailureResult(call, error.code, error.message ?: "网页工具失败")
    } catch (error: LocalModelException) {
        toolFailureResult(call, error.code, error.message ?: "模型请求失败")
    } catch (error: Exception) {
        toolFailureResult(call, "TOOL_ERROR", error.message ?: error::class.java.simpleName)
    }

    private suspend fun executePersistentSubagentTool(
        call: LocalToolCall,
        allowMutation: Boolean,
        sessionId: String,
        memoryTools: LocalMemoryTools,
        enabledOptionalTools: MutableSet<String>,
    ): AgentToolResult {
        val canonical = call.copy(name = LocalToolPolicy.canonical(call.name))
        return try {
            when (canonical.name) {
                "capability_search" -> AgentToolResult(
                    searchCapabilities(canonical.arguments.string("query"), enabledOptionalTools),
                )
                "memory_search", "memory_list" -> AgentToolResult(
                    memoryTools.execute(canonical.name, canonical.arguments, allowMutation = false),
                )
                "tool_output_read" -> AgentToolResult(
                    toolOutputStore.read(
                        sessionId = sessionId,
                        callId = canonical.arguments.string("call_id"),
                        startByte = canonical.arguments.int("start_byte", 0),
                        maxBytes = canonical.arguments.int(
                            "max_bytes",
                            LocalToolOutputStore.DEFAULT_READ_BYTES,
                        ),
                    ),
                )
                "web_fetch" -> {
                    val background = canonical.arguments.boolean("run_in_background", false)
                    if (!background) {
                        executePersistentRegistered(canonical, allowMutation, sessionId)
                    } else {
                        val input = canonical.arguments.string("url")
                        val maxBytes = canonical.arguments.int("max_bytes", DEFAULT_WEB_FETCH_BYTES)
                            .coerceIn(16 * 1024, MAX_WEB_FETCH_BYTES)
                        val format = canonical.arguments.optionalString("format") ?: "text"
                        AgentToolResult(
                            startPersistentWebFetch(
                                url = input,
                                maxBytes = maxBytes,
                                format = format,
                                timeoutSeconds = BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS,
                                sessionId = sessionId,
                            ),
                        )
                    }
                }
                else -> executePersistentRegistered(canonical, allowMutation, sessionId)
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            toolFailureResult(canonical, "TASK_CANCELLED", cancelled.message ?: "子任务自身被取消")
        } catch (error: LocalWebException) {
            toolFailureResult(canonical, error.code, error.message ?: "网页工具失败")
        } catch (error: LocalModelException) {
            toolFailureResult(canonical, error.code, error.message ?: "模型请求失败")
        } catch (error: Exception) {
            toolFailureResult(canonical, "TOOL_ERROR", error.message ?: error::class.java.simpleName)
        }
    }

    private suspend fun executePersistentRegistered(
        original: LocalToolCall,
        allowMutation: Boolean,
        sessionId: String,
    ): AgentToolResult = toolExecutionCoordinator.executeScoped(
        original = original,
        sessionId = sessionId,
        allowMutation = allowMutation,
        planModeEnabled = false,
        approval = { call, tool, summary ->
            if (sessionId == currentSessionId) {
                approve(call, summary, tool)
            } else {
                approvalPreferences.isSafeAutoApprovalEnabled()
            }
        },
    )

    private suspend fun executeAutomationSubagentTool(
        call: LocalToolCall,
        allowMutation: Boolean,
        sessionId: String,
        memoryTools: LocalMemoryTools,
        enabledOptionalTools: MutableSet<String>,
        onApprovalBlocked: (String) -> Unit,
    ): AgentToolResult {
        val canonical = call.copy(name = LocalToolPolicy.canonical(call.name))
        val normalized = if (
            canonical.name in setOf("bash", "run_shell", "web_fetch") &&
            canonical.arguments["run_in_background"]?.jsonPrimitive?.booleanOrNull == true
        ) {
            canonical.copy(
                arguments = JsonObject(
                    canonical.arguments + ("run_in_background" to JsonPrimitive(false)),
                ),
            )
        } else {
            canonical
        }
        val log = eventLogFor(sessionId)
        log.append("tool/call", buildJsonObject {
            put("id", normalized.id)
            put("name", normalized.name)
            put("arguments", normalized.arguments)
            put("automation", true)
        })
        val result = try {
            when (normalized.name) {
                "capability_search" -> AgentToolResult(
                    searchCapabilities(normalized.arguments.string("query"), enabledOptionalTools),
                )
                "memory_search", "memory_list" -> AgentToolResult(
                    memoryTools.execute(normalized.name, normalized.arguments, allowMutation = false),
                )
                "tool_output_read" -> AgentToolResult(
                    toolOutputStore.read(
                        sessionId = sessionId,
                        callId = normalized.arguments.string("call_id"),
                        startByte = normalized.arguments.int("start_byte", 0),
                        maxBytes = normalized.arguments.int(
                            "max_bytes",
                            LocalToolOutputStore.DEFAULT_READ_BYTES,
                        ),
                    ),
                )
                else -> executeAutomationRegistered(
                    original = normalized,
                    allowMutation = allowMutation,
                    sessionId = sessionId,
                    onApprovalBlocked = onApprovalBlocked,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            toolFailureResult(
                normalized,
                "AUTOMATION_TOOL_ERROR",
                error.message ?: error::class.java.simpleName,
            )
        }
        log.append("tool/result", buildJsonObject {
            put("id", normalized.id)
            put("name", normalized.name)
            put("content", truncateWithoutSplittingSurrogatePair(result.content, MAX_EVENT_CHARS))
            put("is_error", result.isError)
            result.errorCode?.let { put("error_code", it) }
            put("retryable", result.retryable)
            put("side_effect", result.sideEffect.name.lowercase())
            result.recoveryHint?.let { put("recovery_hint", it) }
            put("automation", true)
        })
        return result
    }

    private suspend fun executeAutomationRegistered(
        original: LocalToolCall,
        allowMutation: Boolean,
        sessionId: String,
        onApprovalBlocked: (String) -> Unit,
    ): AgentToolResult {
        val result = toolExecutionCoordinator.executeScoped(
            original = original,
            sessionId = sessionId,
            allowMutation = allowMutation,
            planModeEnabled = false,
            approval = { call, tool, _ ->
                if (
                    approvalPreferences.isSafeAutoApprovalEnabled()
                ) {
                    eventLogFor(sessionId).append("approval/auto", buildJsonObject {
                        put("tool", call.name)
                        put("access", tool.access.name.lowercase())
                        put("mode", "automation-global")
                    })
                    true
                } else {
                    val reason = "后台任务需要人工审批：" + tool.name
                    onApprovalBlocked(reason)
                    eventLogFor(sessionId).append("approval/blocked", buildJsonObject {
                        put("tool", call.name)
                        put("access", tool.access.name.lowercase())
                        put("mode", "automation-noninteractive")
                    })
                    false
                }
            },
        )
        return if (result.isError) {
            result.copy(
                recoveryHint = "后台任务不能弹出人工审批；可在工作模式中打开该任务继续处理。" +
                    result.recoveryHint?.let { " " + it }.orEmpty(),
            )
        } else {
            result
        }
    }

    private fun formatToolFailure(call: LocalToolCall, code: String, detail: String): String =
        "[${call.name}][$code] 工具执行失败：$detail\n调用 id：${call.id}"

    private fun toolFailureResult(call: LocalToolCall, code: String, detail: String): AgentToolResult {
        val retryable = code in setOf(
            "MODEL_TIMEOUT",
            "MODEL_NETWORK",
            "TASK_CANCELLED",
            "PARALLEL_TASK_ERROR",
        ) || code.startsWith("MODEL_HTTP_5") || code.contains("TIMEOUT") || code.contains("NETWORK")
        val access = toolRegistry.get(LocalToolPolicy.canonical(call.name))?.access
        val sideEffect = if (
            access in setOf(
                ToolAccess.WORKSPACE_WRITE,
                ToolAccess.SESSION_WRITE,
                ToolAccess.PROCESS,
                ToolAccess.AGENT_CONTROL,
                ToolAccess.DEVICE,
                ToolAccess.PRIVILEGED,
            )
        ) AgentToolSideEffect.POSSIBLE else AgentToolSideEffect.NONE
        val recoveryHint = when {
            sideEffect == AgentToolSideEffect.POSSIBLE ->
                "该调用可能已产生部分副作用；先检查当前状态，再决定是否重试。"
            retryable ->
                "该错误允许重试；网络类错误可先运行 network_diagnose。"
            else ->
                "检查参数、权限或前置状态后再选择其他方案。"
        }
        return AgentToolResult(
            content = formatToolFailure(call, code, detail),
            isError = true,
            errorCode = code,
            retryable = retryable,
            sideEffect = sideEffect,
            recoveryHint = recoveryHint,
        )
    }

    private suspend fun executeRegistered(
        original: LocalToolCall,
        allowMutation: Boolean,
        binding: LocalWorkRunBinding? = null,
    ): AgentToolResult = if (binding == null) {
        toolExecutionCoordinator.execute(original, allowMutation)
    } else {
        toolExecutionCoordinator.executeScoped(
            original = original,
            sessionId = binding.sessionId,
            allowMutation = allowMutation,
            planModeEnabled = binding.state.value.planMode,
            approval = { call, tool, summary ->
                approve(binding, call, summary, tool)
            },
        )
    }

    private fun subagentToolSchemas(
        allowMutation: Boolean,
        allowVirtualScreen: Boolean,
    ): JsonArray = subagentToolSchemas(
        allowMutation = allowMutation,
        allowVirtualScreen = allowVirtualScreen,
        enabledOptional = toolExecutionCoordinator.enabledOptionalSnapshot(),
    )

    private fun subagentToolSchemas(
        allowMutation: Boolean,
        allowVirtualScreen: Boolean,
        enabledOptional: Set<String>,
    ): JsonArray {
        val enabled = enabledOptional +
            if (allowVirtualScreen) SUBAGENT_VIRTUAL_SCREEN_TOOLS else emptySet()
        val tools = toolRegistry.names()
            .mapNotNull(toolRegistry::get)
            .filter { tool -> tool.name !in SUBAGENT_EXCLUDED_TOOLS }
            .filter { tool -> tool.name !in SUBAGENT_VIRTUAL_SCREEN_TOOLS || allowVirtualScreen }
            .filter { tool -> allowMutation || tool.name != "download_file" }
            .filter { tool ->
                allowMutation ||
                    tool.access in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK) ||
                    (allowVirtualScreen && tool.name in SUBAGENT_VIRTUAL_SCREEN_TOOLS)
            }
        return LocalToolRouter.visibleSchemas(tools, enabled)
    }

    private fun modelToolSchemas(
        runPolicy: LocalAgentRunPolicy,
        binding: LocalWorkRunBinding? = null,
    ): JsonArray {
        if (binding == null) return toolExecutionCoordinator.visibleSchemas(runPolicy)
        if (!runPolicy.toolsEnabled) return JsonArray(emptyList())
        val tools = toolRegistry.names().mapNotNull(toolRegistry::get)
        return LocalToolRouter.visibleSchemas(tools, binding.enabledOptionalTools.toSet())
    }

    private fun runToolNames(
        runPolicy: LocalAgentRunPolicy,
        binding: LocalWorkRunBinding?,
    ): List<String> = modelToolSchemas(runPolicy, binding).mapNotNull { element ->
        val function = (element as? JsonObject)?.get("function") as? JsonObject
        (function?.get("name") as? JsonPrimitive)?.contentOrNull
    }

    private fun clearRunCapabilities(binding: LocalWorkRunBinding?) {
        if (binding == null) {
            toolExecutionCoordinator.clearTurnCapabilities()
        } else {
            synchronized(binding.enabledOptionalTools) { binding.enabledOptionalTools.clear() }
        }
    }

    private fun enableRunGitHubCapabilities(binding: LocalWorkRunBinding?) {
        if (binding == null) {
            toolExecutionCoordinator.enableGitHubConnectorTools()
        } else {
            val registered = toolRegistry.names().toSet()
            synchronized(binding.enabledOptionalTools) {
                binding.enabledOptionalTools += setOf(
                    "github_status",
                    "github_api_get",
                    "github_api_request",
                ).filter { it in registered }
            }
        }
    }

    private fun searchCapabilities(query: String): String =
        toolExecutionCoordinator.searchCapabilities(query)

    private fun searchCapabilities(query: String, target: MutableSet<String>): String =
        toolExecutionCoordinator.searchCapabilities(query, target)

    private suspend fun executeBuiltin(call: LocalToolCall, allowMutation: Boolean): String {
        val args = call.arguments
        if (_state.value.planMode && call.name in PLAN_MODE_BLOCKED_TOOLS) {
            return "当前处于规划模式，只能检查和制定方案；请先通过 exit_plan_mode 提交计划。"
        }
        return when (call.name) {
            "read", "read_file" -> workspace.read(
                relativePath = args.string("path"),
                startLine = args.int("start_line", 1),
                endLine = args.int("end_line", args.int("start_line", 1) + 399),
            )
            "tool_output_read" -> toolOutputStore.read(
                sessionId = currentSessionId,
                callId = args.string("call_id"),
                startByte = args.int("start_byte", 0),
                maxBytes = args.int("max_bytes", LocalToolOutputStore.DEFAULT_READ_BYTES),
            )
            "file_inspect" -> fileInspector.inspect(args.string("path"))
            "write", "write_file" -> {
                if (!allowMutation) return "子代理无写入权限"
                val path = args.string("path")
                workspace.write(path, args.string("content"))
            }
            "edit", "edit_file" -> {
                if (!allowMutation) return "该子任务处于只读模式"
                val path = args.string("path")
                workspace.requireFreshObservation(path)
                workspace.edit(path, args.string("old_text"), args.string("new_text"))
            }
            "apply_patch" -> {
                if (!allowMutation) return "该子任务处于只读模式"
                val patch = args.string("patch")
                require(patch.length <= MAX_PATCH_CHARS) { "补丁超过 ${MAX_PATCH_CHARS} 字符上限" }
                validateWorkspacePatchPaths(patch)
                val check = runtimeProcess.execute(
                    ProcessRequest(
                        command = listOf("git", "apply", "--check", "--whitespace=nowarn", "-"),
                        workingDirectory = workspace.path,
                        stdin = patch,
                        timeoutMillis = 30_000L,
                    ),
                )
                if (check.exitCode != 0) {
                    return "[apply_patch][CHECK_FAILED] 补丁预检失败：\n" +
                        (check.stderr.ifBlank { check.stdout }).take(20_000)
                }
                val stat = runtimeProcess.execute(
                    ProcessRequest(
                        command = listOf("git", "apply", "--stat", "-"),
                        workingDirectory = workspace.path,
                        stdin = patch,
                        timeoutMillis = 30_000L,
                    ),
                )
                val applied = runtimeProcess.execute(
                    ProcessRequest(
                        command = listOf("git", "apply", "--whitespace=nowarn", "-"),
                        workingDirectory = workspace.path,
                        stdin = patch,
                        timeoutMillis = 30_000L,
                    ),
                )
                if (applied.exitCode != 0) {
                    return "[apply_patch][APPLY_FAILED] 补丁应用失败：\n" +
                        (applied.stderr.ifBlank { applied.stdout }).take(20_000)
                }
                "补丁已应用" + stat.stdout.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
            }
            "list_files" -> workspace.list(args.optionalString("path") ?: ".", args.int("depth", 3))
            "glob", "glob_files" -> workspace.glob(args.string("pattern"), args.optionalString("path") ?: ".")
            "grep", "search_text" -> workspace.search(
                args.string("query"),
                args.optionalString("path") ?: ".",
                args.boolean("regex", false),
            )
            "bash", "run_shell" -> {
                if (!allowMutation) return "该子任务处于只读模式"
                LocalShellTool.execute(args, workspace, jobs)
            }
            "job_list" -> jobs.list()
            "job_output" -> jobs.output(args.string("job_id"))
            "job_kill" -> jobs.kill(args.string("job_id"))
            "web_search" -> {
                val key = apiKeys.get() ?: error("网页搜索无法读取模型密钥")
                val queries = args["queries"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                web.search(key, queries)
            }
            "web_fetch" -> {
                val input = args.string("url")
                val maxBytes = args.int("max_bytes", DEFAULT_WEB_FETCH_BYTES).coerceIn(16 * 1024, MAX_WEB_FETCH_BYTES)
                val format = args.optionalString("format") ?: "text"
                val background = args.boolean("run_in_background", false)
                val timeout = if (background) BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS else FOREGROUND_WEB_FETCH_TIMEOUT_SECONDS
                if (background) {
                    startPersistentWebFetch(input, maxBytes, format, timeout)
                } else {
                    webTools.fetch(input, maxBytes, format, timeout)
                }
            }
            "http_request" -> {
                if (!allowMutation && args.string("method").uppercase() !in setOf("GET", "HEAD")) {
                    return "只读子任务仅允许 GET/HEAD 请求"
                }
                val headers = args["headers"]?.jsonObject?.mapValues { (_, value) ->
                    value.jsonPrimitive.content
                }.orEmpty()
                webTools.httpRequest(
                    method = args.string("method"),
                    url = args.string("url"),
                    headers = headers,
                    body = args.optionalString("body"),
                    maxBytes = args.int("max_bytes", DEFAULT_WEB_FETCH_BYTES)
                        .coerceIn(16 * 1024, MAX_WEB_FETCH_BYTES),
                    timeoutSeconds = FOREGROUND_WEB_FETCH_TIMEOUT_SECONDS,
                )
            }
            "download_file" -> {
                if (!allowMutation) return "只读子任务不能下载写入文件"
                webTools.download(
                url = args.string("url"),
                path = args.string("path"),
                maxBytes = args.int("max_bytes", DEFAULT_DOWNLOAD_BYTES)
                    .coerceIn(1_024, MAX_DOWNLOAD_BYTES)
                    .toLong(),
                timeoutSeconds = BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS,
            )
            }
            "json_query" -> webTools.jsonQuery(
                path = args.string("path"),
                query = args.optionalString("query").orEmpty(),
            )
            "network_diagnose" -> web.diagnose(args.string("url"))
            "environment_info" -> environmentInfo()
            "capability_search" -> searchCapabilities(args.string("query"))
            "update_plan" -> updatePlan(args)
            "exit_plan_mode" -> exitPlanMode(call, args.string("plan"))
            "todo_write" -> updateTodos(args)
            "create_goal" -> createGoal(args.string("description"))
            "get_goal" -> getGoal()
            "update_goal" -> updateGoal(args.string("status"), args.optionalString("note"))
            "ask_user_question" -> askUser(
                call,
                args.string("question"),
                args["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            )
            "skill" -> args.optionalString("name")?.takeIf(String::isNotBlank)?.let(workspace::readSkill)
                ?: workspace.skills().takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "未安装技能"
            "list_skills" -> workspace.skills().takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "未安装技能"
            "read_skill" -> workspace.readSkill(args.string("name"))
            "subagent", "spawn_subagent" -> {
                val task = args.string("task")
                val model = args.optionalString("model")
                val maxSteps = args.int("max_steps", _state.value.subagentMaxSteps).coerceIn(1, 128)
                val virtualScreen = args.boolean("virtual_screen", false)
                if (args.boolean("run_in_background", false)) {
                    startPersistentReadonlySubagent(
                        task = task,
                        model = model,
                        maxSteps = maxSteps,
                        virtualScreen = virtualScreen,
                    )
                } else subagents.run(
                    task = task,
                    inheritHistory = false,
                    allowMutation = false,
                    modelOverride = model,
                    maxSteps = maxSteps,
                    virtualScreen = virtualScreen,
                )
            }
            "subagent_fork", "fork_subagent" ->
                subagents.run(
                    task = args.string("task"),
                    inheritHistory = true,
                    allowMutation = allowMutation,
                    parentCallId = call.id,
                    maxSteps = _state.value.subagentMaxSteps,
                )
            "list_subagent_models" -> "${_state.value.model}（当前父代理模型）\ndeepseek-flash\ndeepseek-v4-pro"
            "list_agents" -> jobs.listAgents()
            "send_message" -> jobs.send(args.string("agent_id"), args.string("message"))
            "interrupt_agent" -> jobs.kill(args.string("agent_id"))
            "workflow" -> runWorkflow(
                args["tasks"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                args.optionalString("mode") ?: "parallel",
                args["required_evidence"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            )
            "session_search" -> searchSessions(args.string("query"))
            "memory_search", "memory_list", "memory_remember", "memory_update", "memory_forget" ->
                memoryTools.execute(call.name, args, allowMutation)
            "session_event_search" -> eventLogForAuthorized(args.optionalString("session_id")).search(
                query = args.string("query"),
                limit = args.int("limit", 50),
                afterSequence = args.long("after_sequence", -1L),
            )
            "session_trace" -> eventLogForAuthorized(args.optionalString("session_id")).tail(args.int("limit", 40))
            "session_event_trace" -> eventLogForAuthorized(args.optionalString("session_id"))
                .read(args.int("seq", -1).toLong(), before = 1, after = 1)
            "session_event_read" -> eventLogForAuthorized(args.optionalString("session_id")).read(
                sequence = args.int("seq", -1).toLong(),
                before = args.int("before", 0),
                after = args.int("after", 0), offsetChars = args.int("offset_chars", 0),
            )
            "present" -> workspace.present(args.string("path"))
            else -> "未知工具：${call.name}"
        }
    }

    private fun startPersistentWebFetch(
        url: String,
        maxBytes: Int,
        format: String,
        timeoutSeconds: Long,
        sessionId: String = currentSessionId,
    ): String {
        val payload = buildJsonObject {
            put("session_id", sessionId)
            put("url", url)
            put("max_bytes", maxBytes)
            put("format", format)
            put("timeout_seconds", timeoutSeconds)
        }.toString()
        return jobs.startPersistent(
            label = "网页抓取：${url.take(120)}",
            resumeKind = "web_fetch",
            resumePayload = payload,
        ) { _, report ->
            report("正在抓取：$url")
            webTools.fetch(url, maxBytes, format, timeoutSeconds)
        }
    }

    private fun startPersistentReadonlySubagent(
        task: String,
        model: String?,
        maxSteps: Int,
        virtualScreen: Boolean,
    ): String {
        val sessionId = currentSessionId
        val boundState = _state.value
        val boundSubagents = persistentSubagentRunner(sessionId, boundState)
        val payload = buildJsonObject {
            put("session_id", sessionId)
            put("task", task)
            model?.let { put("model", it) }
            put("max_steps", maxSteps)
            put("virtual_screen", virtualScreen)
        }.toString()
        return jobs.startPersistent(
            label = "子代理：${task.take(100)}",
            resumeKind = "subagent_readonly",
            resumePayload = payload,
        ) { jobId, _ ->
            val result = boundSubagents.runResult(
                task = task,
                inheritHistory = false,
                allowMutation = false,
                backgroundJobId = jobId,
                modelOverride = model,
                maxSteps = maxSteps,
                virtualScreen = virtualScreen,
            )
            result.requireCompletedOutput()
        }
    }

    private fun restartInterruptedSafeJobs() {
        synchronized(runStateLock) {
            persistentRecoveryJob?.cancel()
            persistentRecoveryJob = null
        }
        scheduleInterruptedSafeJobs()
    }

    private fun scheduleInterruptedSafeJobs() {
        synchronized(runStateLock) {
            if (persistentRecoveryJob?.isActive == true) return
            persistentRecoveryJob = scope.launch {
                try {
                    while (true) {
                        val targetSession = currentSessionId
                        resumeInterruptedSafeJobsPass(targetSession)
                        val remaining = jobs.interruptedSnapshots().any { snapshot ->
                            interruptedJobSessionId(snapshot, targetSession) == targetSession
                        }
                        if (!remaining) break
                        delay(PERSISTENT_RECOVERY_RETRY_MILLIS)
                    }
                } finally {
                    val completed = currentCoroutineContext()[Job]
                    synchronized(runStateLock) {
                        if (persistentRecoveryJob === completed) persistentRecoveryJob = null
                    }
                }
            }
        }
    }

    private fun resumeInterruptedSafeJobsPass(targetSession: String) {
        jobs.interruptedSnapshots().forEach { snapshot ->
            val payloadText = snapshot.resumePayload
            if (payloadText.isNullOrBlank()) {
                jobs.failInterrupted(snapshot.id, "任务恢复失败：缺少恢复元数据")
                return@forEach
            }
            val payload = try {
                json.parseToJsonElement(payloadText).jsonObject
            } catch (error: Exception) {
                jobs.failInterrupted(snapshot.id, "任务恢复失败：恢复元数据损坏")
                recordJobResumeError(snapshot, targetSession, error)
                return@forEach
            }
            val sessionId = payload["session_id"]?.jsonPrimitive?.contentOrNull ?: targetSession
            if (sessionId != targetSession) return@forEach
            if (jobs.availableSlots() <= 0) return

            try {
                when (snapshot.resumeKind) {
                    "web_fetch" -> {
                        val url = payload["url"]?.jsonPrimitive?.contentOrNull
                            ?: error("恢复任务缺少 url")
                        val maxBytes = payload["max_bytes"]?.jsonPrimitive?.intOrNull
                            ?: DEFAULT_WEB_FETCH_BYTES
                        val format = payload["format"]?.jsonPrimitive?.contentOrNull ?: "text"
                        val timeoutSeconds = payload["timeout_seconds"]?.jsonPrimitive?.contentOrNull
                            ?.toLongOrNull() ?: BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS
                        jobs.resumePersistent(snapshot.id) { _, report ->
                            report("正在恢复网页抓取：$url")
                            webTools.fetch(
                                url,
                                maxBytes.coerceIn(16 * 1024, MAX_WEB_FETCH_BYTES),
                                format,
                                timeoutSeconds.coerceIn(30L, BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS),
                            )
                        }
                    }
                    "subagent_readonly" -> {
                        val task = payload["task"]?.jsonPrimitive?.contentOrNull
                            ?: error("恢复任务缺少 task")
                        val model = payload["model"]?.jsonPrimitive?.contentOrNull
                        val maxSteps = payload["max_steps"]?.jsonPrimitive?.intOrNull
                            ?.coerceIn(1, 128) ?: _state.value.subagentMaxSteps
                        val virtualScreen = payload["virtual_screen"]?.jsonPrimitive?.booleanOrNull ?: false
                        val boundSubagents = persistentSubagentRunner(sessionId, _state.value)
                        jobs.resumePersistent(snapshot.id) { jobId, _ ->
                            val result = boundSubagents.runResult(
                                task = task,
                                inheritHistory = false,
                                allowMutation = false,
                                backgroundJobId = jobId,
                                modelOverride = model,
                                maxSteps = maxSteps,
                                virtualScreen = virtualScreen,
                            )
                            result.requireCompletedOutput()
                        }
                    }
                    else -> {
                        jobs.failInterrupted(
                            snapshot.id,
                            "任务恢复失败：不支持的恢复类型 ${snapshot.resumeKind.orEmpty()}",
                        )
                    }
                }
            } catch (error: Exception) {
                // Persistence failures leave the record interrupted so the coordinator can retry.
                recordJobResumeError(snapshot, sessionId, error)
            }
        }
    }

    private fun interruptedJobSessionId(snapshot: JobSnapshot, fallbackSessionId: String): String? {
        val payloadText = snapshot.resumePayload ?: return null
        return runCatching {
            json.parseToJsonElement(payloadText).jsonObject["session_id"]
                ?.jsonPrimitive?.contentOrNull
                ?: fallbackSessionId
        }.getOrNull()
    }

    private fun recordJobResumeError(snapshot: JobSnapshot, sessionId: String, error: Throwable) {
        eventLogFor(sessionId).append("job/resume-error", buildJsonObject {
            put("job_id", snapshot.id)
            put("kind", snapshot.resumeKind.orEmpty())
            put("detail", (error.message ?: error::class.java.simpleName).take(2_000))
        })
    }
    private suspend fun approve(
        call: LocalToolCall,
        summary: String,
        tool: HarnessTool,
    ): Boolean {
        if (_state.value.deviceApprovalLease && canUseDeviceApprovalLease(tool)) {
            eventLog.append("approval/auto", buildJsonObject {
                put("tool", call.name)
                put("summary", summary)
                put("access", tool.access.name.lowercase())
                put("mode", "device-turn-lease")
            })
            return true
        }
        if (_state.value.safeAutoApprovalEnabled) {
            eventLog.append("approval/auto", buildJsonObject {
                put("tool", call.name)
                put("summary", summary)
                put("access", tool.access.name.lowercase())
                put("impact", approvalImpact(tool).name.lowercase())
                put("mode", "global")
            })
            return true
        }
        return interactions.awaitApproval(
            LocalApproval(
                callId = call.id,
                toolName = call.name,
                summary = summary,
                arguments = call.rawArguments,
                access = tool.access.name.lowercase(),
                impact = approvalImpact(tool),
                canAutoApproveSafely = canAutoApprove(tool, call.arguments),
                canApproveDeviceTurn = canUseDeviceApprovalLease(tool),
            ),
        )
    }

    private suspend fun approve(
        binding: LocalWorkRunBinding,
        call: LocalToolCall,
        summary: String,
        tool: HarnessTool,
    ): Boolean {
        val snapshot = binding.state.value
        if (snapshot.deviceApprovalLease && canUseDeviceApprovalLease(tool)) {
            binding.eventLog.append("approval/auto", buildJsonObject {
                put("tool", call.name)
                put("summary", summary)
                put("access", tool.access.name.lowercase())
                put("mode", "device-turn-lease")
            })
            return true
        }
        if (snapshot.safeAutoApprovalEnabled || approvalPreferences.isSafeAutoApprovalEnabled()) {
            binding.eventLog.append("approval/auto", buildJsonObject {
                put("tool", call.name)
                put("summary", summary)
                put("access", tool.access.name.lowercase())
                put("impact", approvalImpact(tool).name.lowercase())
                put("mode", "global")
            })
            return true
        }
        return binding.interactions.awaitApproval(
            LocalApproval(
                callId = call.id,
                toolName = call.name,
                summary = summary,
                arguments = call.rawArguments,
                access = tool.access.name.lowercase(),
                impact = approvalImpact(tool),
                canAutoApproveSafely = canAutoApprove(tool, call.arguments),
                canApproveDeviceTurn = canUseDeviceApprovalLease(tool),
            ),
        )
    }

    private fun updatePlan(args: JsonObject): String {
        val items = args["items"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: args.optionalString("plan")?.lines()?.filter { it.isNotBlank() }
            ?: emptyList()
        val normalized = items.take(20)
        _state.update { it.copy(plan = normalized) }
        eventLog.append("plan/state", buildJsonObject {
            put("items", JsonArray(normalized.map { item -> JsonPrimitive(item) }))
        })
        persist()
        return if (normalized.isEmpty()) "计划已清空" else "计划已更新，共 ${normalized.size} 项"
    }

    private fun updateTodos(args: JsonObject): String {
        val allowed = setOf("pending", "in_progress", "completed")
        val items = args["items"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val content = item["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val status = item["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (content.isEmpty() || status !in allowed) null else LocalTodoItem(content.take(500), status)
        }.take(50)
        _state.update { it.copy(todos = items) }
        eventLog.append("todo/state", buildJsonObject {
            put("items", JsonArray(items.map { item ->
                buildJsonObject {
                    put("content", item.content)
                    put("status", item.status)
                }
            }))
        })
        persist()
        return if (items.isEmpty()) "任务清单已清空" else "任务清单已更新，共 ${items.size} 项"
    }

    private fun createGoal(description: String): String {
        val goal = LocalGoal(description.trim().take(2_000))
        _state.update { it.copy(goal = goal) }
        eventLog.append("goal/state", buildJsonObject {
            put("description", goal.description)
            put("status", goal.status)
            goal.note?.let { put("note", it) }
        })
        persist()
        return "目标已创建：${goal.description}"
    }

    private fun getGoal(): String {
        val goal = _state.value.goal ?: return "当前会话没有目标"
        return "目标：[${goal.status}] ${goal.description}${goal.note?.let { "\n说明：$it" }.orEmpty()}"
    }

    private fun updateGoal(status: String, note: String?): String {
        require(status in setOf("active", "paused", "completed", "blocked")) { "目标状态无效" }
        val current = _state.value.goal ?: error("当前会话没有目标")
        val updated = current.copy(status = status, note = note?.take(2_000))
        _state.update { it.copy(goal = updated) }
        eventLog.append("goal/state", buildJsonObject {
            put("description", updated.description)
            put("status", updated.status)
            updated.note?.let { put("note", it) }
        })
        persist()
        return "目标状态已更新为 $status"
    }

    private suspend fun askUser(call: LocalToolCall, question: String, options: List<String>): String =
        interactions.awaitQuestion(
            LocalQuestion(call.id, question.take(2_000), options.take(6)),
        )

    private suspend fun exitPlanMode(call: LocalToolCall, plan: String): String {
        if (!_state.value.planMode) return "当前未启用规划模式"
        val answer = askUser(
            call,
            "Harness 已完成计划，是否批准并进入执行模式？\n\n${plan.take(8_000)}",
            listOf("批准并进入执行模式", "继续规划"),
        )
        return if (answer == "批准并进入执行模式") {
            val approvedPlan = plan.lines().map(String::trim).filter(String::isNotEmpty).take(20)
            _state.update {
                it.copy(
                    planMode = false,
                    plan = approvedPlan,
                )
            }
            // Persist the approved plan before leaving planning mode. If the process dies between
            // these two events, recovery stays conservatively in planning mode with the approved
            // plan instead of entering execution mode without its durable plan.
            eventLog.append("plan/state", buildJsonObject {
                put("items", JsonArray(approvedPlan.map { item -> JsonPrimitive(item) }))
            })
            eventLog.append("plan/mode", buildJsonObject { put("active", false) })
            if (modelHistory.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
                val prompt = systemPrompt()
                modelHistory.replaceSystem(
                    buildJsonObject { put("role", "system"); put("content", prompt) },
                )
                eventLog.append("system/prompt", buildJsonObject { put("content", prompt) })
                updateContextMetrics()
            }
            persist()
            "计划已获批准，已进入执行模式"
        } else {
            "用户要求继续规划。反馈：$answer"
        }
    }

    private suspend fun runWorkflow(tasks: List<String>, mode: String, requiredEvidence: List<String>): String {
        _state.update { it.copy(workflowProgress = null) }
        return LocalWorkflowCoordinator(
            execute = { prompt -> subagents.runResult(
                task = prompt,
                inheritHistory = false,
                allowMutation = false,
                maxSteps = _state.value.subagentMaxSteps,
            ).requireCompletedOutput() },
            pruneOutput = ::pruneToolResult,
            onProgress = { progress -> _state.update { state ->
                val previousBlock = state.workflowProgress?.takeIf { it.needsUserAction }
                val blocked = progress.stage == "受阻"
                state.copy(workflowProgress = LocalWorkflowProgress(
                    sessionId = currentSessionId,
                    stage = progress.stage,
                    task = progress.task,
                    completed = progress.completed,
                    total = progress.total,
                    blockedReason = if (blocked) progress.detail else previousBlock?.blockedReason,
                    needsUserAction = blocked || previousBlock != null,
                ))
            } },
        ).run(tasks, mode, requiredEvidence)
    }

    private fun searchSessions(query: String): String {
        val hits = (sessionSummaries().map(LocalSessionSummary::id) + currentSessionId).distinct().mapNotNull { id ->
            val result = eventLogFor(id).search(query, limit = 1)
            result.takeUnless { it == "未找到会话事件" || it == "会话事件日志为空" }
                ?.let { "会话 $id\n$it" }
        }
        return if (hits.isEmpty()) "未找到历史会话事件" else hits.take(50).joinToString("\n\n")
    }

    private fun eventLogForAuthorized(requestedId: String?): LocalSessionEventLog {
        val id = requestedId?.takeIf(String::isNotBlank) ?: currentSessionId
        require(id == currentSessionId || sessionSummaries().any { it.id == id }) { "会话不存在或无权访问：$id" }
        return eventLogFor(id)
    }

    internal fun transcriptPageForUi(
        sessionId: String,
        cursor: LocalTranscriptPageCursor? = null,
        limit: Int = 200,
    ): LocalTranscriptPage = LocalSessionTranscriptPager(
        eventLog = eventLogForAuthorized(sessionId),
    ).page(
        cursor = cursor,
        limit = limit,
    )

    internal fun transcriptTailForUi(
        sessionId: String,
        limit: Int,
    ): List<LocalHarnessMessage> = LocalSessionTranscriptPager(
        eventLog = eventLogForAuthorized(sessionId),
    ).page(limit = limit).messages

    internal fun completeTranscriptForUi(
        sessionId: String,
    ): List<LocalHarnessMessage> = LocalSessionTranscriptPager(
        eventLog = eventLogForAuthorized(sessionId),
    ).all()

    private fun cancelChatPostTurn() {
        chatContextRefreshCoordinator.cancelScheduledRefresh()
    }

    private fun scheduleChatPostTurn(
        userMessage: String,
        assistantMessage: String,
        persona: PersonaProfile,
        expectedSessionId: String,
        expectedAssistantMessageId: String,
        expectedBaseState: ChatCharacterState,
        sourceUserMessageId: String? = null,
    ) {
        chatContextRefreshCoordinator.schedule(
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            persona = persona,
            expectedSessionId = expectedSessionId,
            expectedAssistantMessageId = expectedAssistantMessageId,
            expectedBaseState = expectedBaseState,
            boundEventLog = eventLogFor(expectedSessionId),
            sourceUserMessageId = sourceUserMessageId,
        )
    }

    private suspend fun enforceChatStyle(
        key: String,
        snapshot: LocalHarnessState,
        messages: List<JsonObject>,
        step: Int,
        reply: LocalModelReply,
        userMessage: String,
    ): LocalModelReply = chatReplyCoordinator.finalizeDirect(
        snapshot = snapshot,
        reply = reply,
        userMessage = userMessage,
        step = step,
        retryRaw = { repairHint ->
            completeWithRetry(
                key = key,
                snapshot = snapshot,
                messages = withEphemeralContext(messages, repairHint),
                step = step,
                toolsOverride = JsonArray(emptyList()),
                publishPreview = false,
                maxAttemptsOverride = 1,
                allowContextOverflowRecovery = false,
                temperature = CHAT_ROLEPLAY_TEMPERATURE,
            )
        },
        appendEvent = { type, data ->
            eventLog.append(type, data)
        },
    )

    private suspend fun completeWithRetry(
        key: String,
        snapshot: LocalHarnessState,
        messages: List<JsonObject>,
        step: Int,
        toolsOverride: JsonArray? = null,
        publishPreview: Boolean = true,
        maxAttemptsOverride: Int? = null,
        allowContextOverflowRecovery: Boolean = true,
        persistOverflowHistory: Boolean = false,
        streamFilterPhrases: List<String> = emptyList(),
        requestLog: LocalSessionEventLog? = null,
        temperature: Double? = null,
        binding: LocalWorkRunBinding? = null,
    ): LocalModelReply = modelRequestCoordinator.complete(
        key = key,
        snapshot = snapshot,
        messages = messages,
        step = step,
        toolsOverride = toolsOverride,
        publishPreviewEnabled = publishPreview,
        maxAttemptsOverride = maxAttemptsOverride,
        allowContextOverflowRecovery = allowContextOverflowRecovery,
        persistOverflowHistory = persistOverflowHistory,
        streamFilterPhrases = streamFilterPhrases,
        requestLog = requestLog ?: binding?.eventLog,
        temperature = temperature,
        previewGuard = {
            binding == null || currentSessionId == binding.sessionId
        },
        overflowPersister = binding?.let { runBinding ->
            { snapshot, mode -> persistOverflowCompaction(snapshot, mode, runBinding) }
        },
    )

    private fun persistForegroundOverflowCompaction(
        snapshot: LocalHarnessState,
        summaryMode: LocalHistorySummaryMode,
    ) {
        if (snapshot.sessionId != currentSessionId || snapshot.groupChat.enabled) return
        persistOverflowCompaction(
            snapshot = snapshot,
            summaryMode = summaryMode,
            binding = null,
        )
    }

    private fun persistOverflowCompaction(
        snapshot: LocalHarnessState,
        summaryMode: LocalHistorySummaryMode,
        binding: LocalWorkRunBinding?,
    ) {
        if (binding != null && snapshot.sessionId != binding.sessionId) return
        val history = binding?.modelHistory ?: modelHistory
        val log = binding?.eventLog ?: eventLog
        val compaction = history.compactOverflow(
            compactor = historyCompactor,
            summaryMode = summaryMode,
        ) ?: return
        log.append("session/compaction", buildJsonObject {
            put("trigger", "context-overflow")
            put("omitted_messages", compaction.omittedMessages)
            put("summary", compaction.summary)
            put("estimated_tokens_before", compaction.estimatedTokensBefore)
            put("estimated_tokens_after", compaction.estimatedTokensAfter)
        })
        checkpointModelHistory("session/context-overflow", binding)
        updateContextMetrics(binding)
        persist(binding)
    }

    private fun currentHistoryBudget(binding: LocalWorkRunBinding? = null): LocalHistoryBudget {
        val snapshot = binding?.state?.value ?: _state.value
        return localHistoryBudgetFor(
            memoryClassMb = memoryClassMb,
            pressure = resourceScheduler.snapshot().pressure,
            model = snapshot.model,
            baseUrl = snapshot.baseUrl,
        )
    }

    private fun updateContextMetrics(binding: LocalWorkRunBinding? = null) {
        val budget = currentHistoryBudget(binding)
        val history = binding?.modelHistory ?: modelHistory
        val targetState = binding?.state ?: _state
        targetState.update {
            it.copy(
                contextChars = history.encodedChars,
                contextBudgetChars = budget.maxHistoryChars,
            )
        }
    }

    private fun pruneToolResult(result: String): String =
        retainToolResult(
            sessionId = currentSessionId,
            callId = null,
            result = result,
        )

    private fun retainToolResult(
        sessionId: String,
        callId: String?,
        result: String,
    ): String {
        val budget = currentHistoryBudget()
        val retained = retainTextForModel(
            value = result,
            maxTokens = budget.maxToolResultTokens,
            maxChars = budget.maxToolResultChars,
        )
        if (!retained.truncated) return retained.text
        val stored = callId?.let { toolOutputStore.store(sessionId, it, result) } != null
        val recovery = when {
            callId == null -> "请缩小查询范围后继续读取。"
            stored -> "可调用 tool_output_read，并传入 call_id=$callId 分段读取完整结果。"
            else -> "完整结果超过本机私有保留上限；请缩小原查询后重试。"
        }
        return retained.text +
            "\n[已从模型上下文省略 ${retained.omittedBytes} 个 UTF-8 字节；$recovery]"
    }

    private fun compactHistoryIfNeeded(
        extraTokens: Int = 0,
        binding: LocalWorkRunBinding? = null,
    ) {
        val budget = currentHistoryBudget(binding)
        val history = binding?.modelHistory ?: modelHistory
        val targetState = binding?.state ?: _state
        val log = binding?.eventLog ?: eventLog
        val compaction = history.compact(
            compactor = historyCompactor,
            budget = budget,
            extraTokens = extraTokens,
            summaryMode = if (targetState.value.usageMode == LocalUsageMode.CHAT) {
                LocalHistorySummaryMode.CHAT
            } else {
                LocalHistorySummaryMode.WORK
            },
        ) ?: run {
            updateContextMetrics(binding)
            return
        }
        log.append(
            "session/compaction",
            buildJsonObject {
                put("omitted_messages", compaction.omittedMessages)
                put("summary", compaction.summary)
                put("estimated_tokens_before", compaction.estimatedTokensBefore)
                put("estimated_tokens_after", compaction.estimatedTokensAfter)
                put("extra_request_tokens", extraTokens)
            },
        )
        checkpointModelHistory("session/compaction", binding)
        updateContextMetrics(binding)
        persist(binding)
    }

    private fun ensureSystemMessage(binding: LocalWorkRunBinding? = null) {
        val history = binding?.modelHistory ?: modelHistory
        val log = binding?.eventLog ?: eventLog
        if (history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") return
        val prompt = systemPrompt(binding)
        history.prepend(
            buildJsonObject {
                put("role", "system")
                put("content", prompt)
            },
        )
        log.append("system/prompt", buildJsonObject { put("content", prompt) })
        updateContextMetrics(binding)
    }

    private fun systemPrompt(binding: LocalWorkRunBinding? = null): String {
        val snapshot = binding?.state?.value ?: _state.value
        return when {
            snapshot.usageMode != LocalUsageMode.CHAT ->
                workSystemPrompt(workspace.path, snapshot.planMode)
            snapshot.groupChat.enabled -> groupChatSystemPrompt()
            else -> chatSystemPrompt()
        }
    }

    private fun environmentInfo(): String {
        val commands = listOf(
            "sh", "ls", "cat", "cp", "mv", "rm", "mkdir", "sed", "grep", "find",
            "git", "curl", "wget", "python3", "python", "node",
        ).filter(runtimeProcess::isCommandAvailable)
        return LocalEnvironmentReport.build(
            workspacePath = workspace.path,
            resources = resourceScheduler.snapshot(),
            contextChars = modelHistory.encodedChars,
            contextBudgetChars = currentHistoryBudget().maxHistoryChars,
            pendingInputs = pendingInputs.size(),
            pendingInputLimit = MAX_PENDING_INPUTS,
            commands = commands,
            runtimeStatuses = listOf(bundledNodeRuntime.status(), bundledPythonRuntime.status(), bundledGitRuntime.status()),
            recentDiagnostics = AppLog.snapshot(),
        ) + "\n" + LocalSessionArchiveMaintenance.storageStatus(sessionsRoot) +
            "\n" + LocalProcessExitStatus.read(context)
    }

    private suspend fun load() {
        val storedModel = preferences.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        val model = modelConfiguration.normalizeModel(storedModel)
        if (model != storedModel) {
            preferences.edit().putString(KEY_MODEL, model).apply()
        }
        val baseUrl = preferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        if (!preferences.contains(KEY_MODEL_PROFILES)) {
            val oldModels = if (apiKeys.hasLegacyCredential()) modelConfiguration.legacyConfiguredModelNames(model) else emptyList()
            val profiles = oldModels.map { name ->
                LocalModelProfile(modelProfileId(name, baseUrl), name, baseUrl)
            }
            // Save the routes before moving the legacy secret so interrupted migration can retry.
            preferences.edit().putString(KEY_MODEL_PROFILES, modelConfiguration.encodeProfiles(profiles)).apply()
            apiKeys.migrate(profiles.map(LocalModelProfile::id))
        } else {
            apiKeys.migrate(modelConfiguration.readProfiles().filter { it.baseUrl == baseUrl }
                .map(LocalModelProfile::id))
        }
        apiKeys.activate(modelProfileId(model, baseUrl))
        loadSession(currentSessionId, model, baseUrl)
    }

    private suspend fun loadSession(
        sessionId: String,
        model: String = preferences.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
        baseUrl: String = preferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
    ) {
        val loaded = try {
            sessionCoordinator.readWithLegacyApproval(sessionId)
        } catch (future: FutureSessionVersionException) {
            _state.update {
                it.copy(
                    loading = false,
                    sessionId = sessionId,
                    error = future.message,
                )
            }
            return
        }
        // Only mutate the durable event tail after the persisted session format is accepted.
        // A future-version session must remain completely untouched.
        val recovery = eventLog.repairInterruptedTail()
        val stored = loaded?.session ?: LocalHarnessSession(id = sessionId)
        val legacyProjectionBaseline = if (stored.controlProjectedThroughSequence == null && loaded != null) {
            eventLog.latest(PROJECTION_BASELINE_EVENT)?.sequence ?: eventLog.append(
                PROJECTION_BASELINE_EVENT,
                buildJsonObject { put("source", "legacy-session-snapshot") },
            ).sequence
        } else {
            null
        }
        val projectionCursor = projectionReplayCursor(
            snapshot = stored,
            persistedSnapshotExists = loaded != null,
            legacyBaselineSequence = legacyProjectionBaseline,
        )
        val projectedControls = projectSessionControlTail(
            snapshot = stored,
            events = eventLog.snapshotAfter(projectionCursor),
            sequenceExclusive = projectionCursor,
        )
        val restoredTranscript = sessionCoordinator.restoreTranscript(
            stored = stored,
            persistedSnapshotExists = loaded != null,
        )
        transcriptProjectionCursor = restoredTranscript.projectedThroughSequence
        val restoredHistory = restoreLocalModelHistory(
            events = loadModelHistoryReplayEvents(eventLog, modelHistoryCheckpointCodec, stored.legacyModelHistory),
            legacyFallback = stored.legacyModelHistory,
            codec = modelHistoryCheckpointCodec,
        )
        modelHistory.reset(restoredHistory.messages)
        buildRecoveredToolResultMessages(modelHistory.snapshot(), recovery).forEach { modelHistory.append(it) }
        val restoredInbox = eventLog.latest(LOCAL_AGENT_INBOX_EVENT_TYPE)
            ?.let { event -> decodeLocalAgentInboxPending(event.data) }
            .orEmpty()
        pendingInputs.restore(restoredInbox)
        var runRecoveryError: String? = null
        agentRunCoordinator.recoveryDecision(sessionId, recovery)?.let { decision ->
            val blocked = decision.blockedReason
            if (blocked != null) {
                runRecoveryError = blocked
                agentRunCoordinator.markRecoveryBlocked(sessionId, decision.runId, blocked)
            } else {
                val queued = decision.queuedInput
                if (queued != null && pendingInputs.snapshot().none { it.id == queued.id }) {
                    if (pendingInputs.offer(queued)) {
                        eventLog.append(
                            LOCAL_AGENT_INBOX_EVENT_TYPE,
                            encodeLocalAgentInboxEvent(
                                action = "recovered-run",
                                pending = pendingInputs.snapshot(),
                                affected = listOf(queued),
                            ),
                        )
                        agentRunCoordinator.markRecoveryQueued(sessionId, decision.runId)
                    } else {
                        runRecoveryError = "上次任务可以安全续跑，但待处理输入队列已满，请先处理现有任务。"
                        agentRunCoordinator.markRecoveryBlocked(
                            sessionId,
                            decision.runId,
                            runRecoveryError.orEmpty(),
                        )
                    }
                }
            }
        }
        val profile = userProfileStore.read()
        val restoredLineageId = stored.lineageId.ifBlank { stored.id.ifBlank { sessionId } }
        val restoredProjectId = stored.projectId ?: when (stored.conversationMode) {
            LocalConversationMode.INDEPENDENT -> null
            LocalConversationMode.PROJECT,
            LocalConversationMode.CONTINUATION -> LOCAL_PROJECT_ID
        }
        _state.value = LocalHarnessState(
            loading = false,
            configured = apiKeys.get() != null,
            model = model,
            baseUrl = baseUrl,
            configuredModels = modelConfiguration.readProfiles().map(LocalModelProfile::model).distinct().sorted(),
            modelProfiles = modelConfiguration.readProfiles(),
            mainMaxSteps = preferences.getInt(LocalHarnessSettingsCoordinator.KEY_MAIN_MAX_STEPS, DEFAULT_MAIN_MAX_STEPS).coerceIn(4, 128),
            subagentMaxSteps = preferences.getInt(LocalHarnessSettingsCoordinator.KEY_SUBAGENT_MAX_STEPS, DEFAULT_SUBAGENT_MAX_STEPS).coerceIn(1, 128),
            modelAttempts = preferences.getInt(LocalHarnessSettingsCoordinator.KEY_MODEL_ATTEMPTS, DEFAULT_MODEL_ATTEMPTS).coerceIn(1, 5),
            imageInputMode = runCatching {
                LocalImageInputMode.valueOf(
                    preferences.getString(LocalHarnessSettingsCoordinator.KEY_IMAGE_INPUT_MODE, LocalImageInputMode.AUTO.name)
                        ?: LocalImageInputMode.AUTO.name,
                )
            }.getOrDefault(LocalImageInputMode.AUTO),
            workspacePath = workspace.path,
            sessionId = sessionId,
            usageMode = stored.usageMode,
            personaId = stored.personaId,
            galleryId = stored.galleryId,
            galleryStoryId = stored.galleryStoryId,
            gallerySaveSuppressedThrough = stored.gallerySaveSuppressedThrough,
            chatPersona = chatPersonaStore.get(stored.personaId),
            chatState = stored.chatState,
            chatContext = stored.chatContext.withLegacyFallback(stored.chatState),
            replySuggestions = stored.replySuggestions,
            chatBranches = if (stored.usageMode == LocalUsageMode.CHAT && !stored.groupChat.enabled) {
                restoreMaterializedChatBranchState(
                    current = projectedControls.chatBranches,
                    activeMessages = restoredTranscript.messages,
                    chatState = stored.chatState,
                    replySuggestions = stored.replySuggestions,
                )
            } else {
                LocalChatBranchState()
            },
            groupChat = if (stored.usageMode == LocalUsageMode.CHAT) stored.groupChat else LocalGroupChatState(),
            conversationMode = stored.conversationMode,
            parentSessionId = stored.parentSessionId,
            lineageId = restoredLineageId,
            projectId = restoredProjectId,
            handoffSummary = stored.handoffSummary,
            userRules = profile.customRules,
            autoRecall = profile.autoRecall,
            autoMemory = profile.autoMemory,
            usage = usageTracker.state.value,
            sessions = sessionSummaries(),
            messages = restoredTranscript.messages,
            transcriptIndex = restoredTranscript.index,
            plan = projectedControls.plan,
            todos = projectedControls.todos,
            goal = projectedControls.goal,
            planMode = projectedControls.planMode,
            safeAutoApprovalEnabled = approvalPreferences.isSafeAutoApprovalEnabled(
                loaded?.legacySafeAutoApproval == true,
            ),
            jobs = projectExecutionJobs(stored.usageMode, jobs.snapshotInfos()),
            queuedInputCount = pendingInputs.size(),
            activeModelRequests = resourceScheduler.snapshot().activeModelRequests,
            activeAgents = projectWorkResourceCount(
                stored.usageMode,
                resourceScheduler.snapshot().activeAgents,
            ),
            activeTerminals = projectWorkResourceCount(
                stored.usageMode,
                resourceScheduler.snapshot().activeTerminals,
            ),
            activeVirtualDisplays = projectWorkResourceCount(
                stored.usageMode,
                resourceScheduler.snapshot().activeVirtualDisplays,
            ),
            activeLanguageServers = projectWorkResourceCount(
                stored.usageMode,
                resourceScheduler.snapshot().activeLanguageServers,
            ),
            maxModelRequests = resourceScheduler.budget.maxModelRequests,
            maxAgents = resourceScheduler.budget.maxAgents,
            maxTerminals = resourceScheduler.budget.maxTerminals,
            maxVirtualDisplays = resourceScheduler.budget.maxVirtualDisplays,
            maxLanguageServers = resourceScheduler.budget.maxLanguageServers,
            resourcePressure = resourceScheduler.snapshot().pressure.name.lowercase(),
            contextChars = modelHistory.encodedChars,
            contextBudgetChars = currentHistoryBudget().maxHistoryChars,
            error = runRecoveryError,
        )
        var wroteHistoryCheckpoint = false
        if (_state.value.groupChat.enabled) {
            // Group model history is already restored from its durable checkpoint/event tail.
            // Rebuilding it from the bounded UI transcript would silently discard older context.
            refreshGroupModelSystemPrompt()
            checkpointModelHistory("load/group-system-refresh")
            wroteHistoryCheckpoint = true
        } else if (modelHistory.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
            modelHistory.replaceSystem(
                buildJsonObject { put("role", "system"); put("content", systemPrompt()) },
            )
            updateContextMetrics()
            checkpointModelHistory("load/system-refresh")
            wroteHistoryCheckpoint = true
        } else if (recovery.repaired || restoredHistory.checkpointRecommended) {
            checkpointModelHistory(
                if (restoredHistory.usedLegacyFallback) "load/legacy-history-migration"
                else "load/event-replay",
            )
            wroteHistoryCheckpoint = true
        }
        if (restoredHistory.usedLegacyFallback && !wroteHistoryCheckpoint) {
            checkpointModelHistory("load/legacy-history-migration")
        }
        if (
            restoredHistory.usedLegacyFallback ||
            restoredTranscript.needsPersist
        ) {
            // Materialize migrated/replayed projections so later restarts only fold the new tail.
            persist()
        }
    }

    /**
     * Restore model history from the newest valid checkpoint tail whenever possible.
     *
     * New sessions checkpoint model-visible history regularly. Reading the entire event archive
     * here would undo the projection-cursor startup optimization for long-lived sessions. Legacy
     * history still provides the one-time fallback when no valid checkpoint exists.
     */
    private fun maybeCleanupUnreferencedLocalImages() {
        val now = System.currentTimeMillis()
        val last = preferences.getLong(KEY_ATTACHMENT_GC_AT, 0L)
        if (now - last < ATTACHMENT_GC_INTERVAL_MILLIS) return
        runCatching { cleanupUnreferencedLocalImagesNow(sessionsRoot, currentSessionId, ::eventLogFor, modelHistory.snapshot(), workspace.path, eventLog) }
            .onSuccess {
                preferences.edit().putLong(KEY_ATTACHMENT_GC_AT, now).apply()
            }
    }

    private fun checkpointModelHistory(
        reason: String,
        binding: LocalWorkRunBinding? = null,
    ) {
        val log = binding?.eventLog ?: eventLog
        val history = binding?.modelHistory ?: modelHistory
        log.append(
            ModelHistoryCheckpointCodec.EVENT_TYPE,
            modelHistoryCheckpointCodec.encode(history.snapshot(), reason),
        )
        if (binding != null) {
            binding.turnsSinceModelHistoryCheckpoint = 0
        } else {
            turnsSinceModelHistoryCheckpoint = 0
        }
    }

    private fun checkpointModelHistoryAtTurnBoundary(
        reason: String,
        binding: LocalWorkRunBinding? = null,
    ) {
        if (binding != null) {
            binding.turnsSinceModelHistoryCheckpoint += 1
            if (binding.turnsSinceModelHistoryCheckpoint >= MODEL_HISTORY_CHECKPOINT_TURN_INTERVAL) {
                checkpointModelHistory(reason, binding)
            }
        } else {
            turnsSinceModelHistoryCheckpoint += 1
            if (turnsSinceModelHistoryCheckpoint >= MODEL_HISTORY_CHECKPOINT_TURN_INTERVAL) {
                checkpointModelHistory(reason)
            }
        }
    }

    private fun persist(binding: LocalWorkRunBinding? = null) {
        // Capture the durable boundary before the in-memory projection. A concurrent state update
        // may then be included in the snapshot with an older cursor, which is safe because replay
        // can idempotently re-apply its later event.
        val sessionId = binding?.sessionId ?: currentSessionId
        val state = binding?.state?.value ?: _state.value
        val log = binding?.eventLog ?: eventLog
        val cursor = binding?.transcriptProjectionCursor ?: transcriptProjectionCursor
        val snapshot = sessionCoordinator.snapshot(
            sessionId = sessionId,
            state = state,
            controlProjectedThroughSequence = log.latestSequence(),
            transcriptProjectedThroughSequence = cursor,
        )
        sessionCoordinator.enqueue(snapshot)
    }

    private fun sessionFileFor(id: String) = File(sessionsRoot, "$id.json")

    private fun eventLogFor(id: String) = LocalSessionEventLog(File(sessionsRoot, "$id.events.jsonl"), json)

    private fun sessionSummaries(): List<LocalSessionSummary> = try {
        sessionCoordinator.summaries()
    } catch (future: FutureSessionVersionException) {
        _state.update { it.copy(error = future.message) }
        emptyList()
    }





}