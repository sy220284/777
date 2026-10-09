package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.observability.AppLog
import android.content.Context
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.local.LocalModelRequestCoordinator
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.LocalToolApprovalRuntime
import com.labteto.dshmobile.local.LocalToolCompositionRoot
import com.labteto.dshmobile.local.context.ContextComposer
import com.labteto.dshmobile.local.project.ProjectContextPort
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.model.localImageRequestBudgetForModelConcurrency
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
import com.labteto.dshmobile.local.runtime.MAX_EVENT_CHARS
import com.labteto.dshmobile.local.runtime.SUBAGENT_EXCLUDED_TOOLS
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.shouldAutoApproveTool
import com.labteto.dshmobile.local.tools.LocalToolPolicy
import com.labteto.dshmobile.local.tools.int
import com.labteto.dshmobile.local.tools.string
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** WorkFeature composition owner for foreground turns, subagents and Work-owned built-ins. */
@Singleton
internal class LocalWorkComposition @Inject constructor(
    @ApplicationContext context: Context,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val modelGateway: LocalModelGateway,
    modelRequests: LocalModelRequestCoordinator,
    usageTracker: DeepSeekUsageTracker,
    contextComposer: ContextComposer,
    projectContext: ProjectContextPort,
    private val workRunRegistry: LocalWorkRunRegistry,
    workMemoryRuntime: LocalWorkMemoryRuntime,
    workModelHistoryRuntime: LocalWorkModelHistoryRuntime,
    workSessionProjection: LocalWorkSessionProjectionRuntime,
    private val approvalPreferences: LocalApprovalPreferences,
    private val tools: LocalToolCompositionRoot,
    memoryStore: MemoryStore,
    memoryManager: MemoryManager,
    private val toolApproval: LocalToolApprovalRuntime,
    json: Json,
) {
    private val backgroundTools = LocalWorkBackgroundToolExecutor(runtimeStateStore, sessionStorage,
        approvalPreferences, tools, toolApproval)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toolResultRuntime = LocalWorkToolResultRuntime(
        runtimeStateStore = runtimeStateStore,
        toolOutputStore = tools.toolOutputStore,
    )
    private val turnToolRuntime = LocalWorkTurnToolRuntime(
        registry = tools.registry,
        execution = tools.execution,
        schemas = tools.schemas,
        approvalPreferences = approvalPreferences,
        githubConfigured = tools.plugins::githubConfigured,
    )
    private val subagentFactory by lazy {
        LocalSubagentRunnerFactory(
            modelGateway = modelGateway,
            jobs = runtimeStateStore.jobManager,
            modelHistory = runtimeStateStore.foregroundRunHandle.modelHistory,
            toolOutputStore = tools.toolOutputStore,
            workspacePath = sessionStorage.files.workspace.path,
            imageRequestBudget = localImageRequestBudgetForModelConcurrency(
                runtimeStateStore.resourceBudget.maxModelRequests,
            ),
            imageCapabilities = runtimeStateStore.imageCapabilities,
            usageTracker = usageTracker,
            resourceScheduler = runtimeStateStore.resourceScheduler,
            virtualDisplayProvider = tools.plugins.virtualDisplayProvider,
            memoryClassMb = runtimeStateStore.memoryClassMb,
            agentRunCoordinator = sessionStorage.agentRunCoordinator,
            contextComposer = contextComposer,
            projectContext = projectContext,
            memoryStore = memoryStore,
            memoryManager = memoryManager,
            eventLogFor = sessionStorage.eventLogs::get,
            defaultState = runtimeStateStore.state,
            defaultSessionId = runtimeStateStore::currentSessionId,
            compactionPolicy = LocalWorkSubagentCompactionPolicy,
        )
    }
    private val subagents by lazy {
        LocalWorkSubagentRuntime(
            factory = subagentFactory,
            schemasProvider = { allowMutation, allowVirtualScreen, enabledOptional ->
                tools.schemas.subagentSchemas(
                    allowMutation = allowMutation,
                    allowVirtualScreen = allowVirtualScreen,
                    enabledOptional = enabledOptional,
                )
            },
            executeTool = { binding, call, allowMutation, _, _ ->
                turnToolRuntime.execute(binding, call, allowMutation)
            },
            pruneOutput = { binding, value -> toolResultRuntime.retain(binding, null, value) },
        )
    }
    private val persistentJobs by lazy {
        LocalPersistentJobRecoveryCoordinator(
            scope = scope,
            jobs = runtimeStateStore.jobManager,
            json = json,
            modelGateway = modelGateway,
            webTools = tools.webTools,
            currentSessionId = runtimeStateStore::currentSessionId,
            currentState = { runtimeStateStore.state.value },
            defaultHistory = runtimeStateStore.foregroundRunHandle.modelHistory::snapshot,
            stateForSession = { sessionId ->
                workRunRegistry[sessionId]?.aggregateSnapshot()
                    ?: runtimeStateStore.state.value.takeIf { state -> state.sessionId == sessionId }
            },
            historyForSession = { sessionId ->
                workRunRegistry[sessionId]?.runHandle?.modelHistory?.snapshot()
                    ?: runtimeStateStore.foregroundRunHandle.modelHistory.snapshot()
                        .takeIf { runtimeStateStore.state.value.sessionId == sessionId }
            },
            subagentRunner = { sessionId, boundState, history ->
                subagentFactory.createBound(
                    sessionId = sessionId,
                    boundState = boundState,
                    runKind = LocalAgentRunKind.SUBAGENT,
                    schemasProvider = { allowMutation, allowVirtualScreen, enabledOptional ->
                        tools.schemas.subagentSchemas(
                            allowMutation = allowMutation,
                            allowVirtualScreen = allowVirtualScreen,
                            enabledOptional = enabledOptional,
                        )
                    },
                    executeTool = { call, allowMutation, memoryTools, enabledOptional ->
                        backgroundTools.executePersistentSubagentTool(
                            call = call,
                            allowMutation = allowMutation,
                            sessionId = sessionId,
                            memoryTools = memoryTools,
                            enabledOptionalTools = enabledOptional,
                        )
                    },
                    historySnapshot = history,
                    modelAdmission = (
                        workRunRegistry[sessionId]?.executionControl ?: LocalWorkExecutionControl()
                    ).asModelAdmissionPort(),
                )
            },
            eventLogFor = sessionStorage.eventLogs::get,
        )
    }
    private val taskBuiltins = LocalWorkBuiltinToolRuntime(
        persist = { binding -> sessionStorage.coordinator.enqueue(binding.persistenceSnapshot()) },
        updateContextMetrics = workModelHistoryRuntime::updateContextMetrics,
    )
    private val agentTeams: LocalAgentTeamRuntime by lazy {
        LocalAgentTeamRuntime(
            jobs = runtimeStateStore.jobManager,
            startTeammate = {
                    binding,
                    requestedJobId,
                    task,
                    instructions,
                    model,
                    maxSteps,
                    context,
                    parentCallId,
                    grantedExtensions,
                ->
                // 授权必须命中当前实际注册的扩展工具，不能静默丢弃或绕过设备/团队边界。
                val unavailable = grantedExtensions.filter { name ->
                    name in SUBAGENT_EXCLUDED_TOOLS ||
                        tools.registry.get(name)?.exposure != ToolExposure.OPTIONAL
                }
                require(unavailable.isEmpty()) {
                    "TEAM_EXTENSION_UNAVAILABLE：工具未注册或不能下放：" + unavailable.sorted().joinToString("、")
                }
                if (grantedExtensions.any { name ->
                    tools.registry.get(name)?.metadata?.family.equals("GitHub", ignoreCase = true)
                }) {
                    require(tools.plugins.githubConfigured()) {
                        "TEAM_GITHUB_CREDENTIAL_REQUIRED：请先配置 GitHub 连接凭据"
                    }
                }
                val defaultOptional = setOf(
                    "skill", "web_search", "web_fetch", "job_list", "job_output",
                    "job_kill", "json_query", "environment_info", "download_file",
                )
                val activeOptional = defaultOptional + grantedExtensions
                val exposed = tools.schemas.names(
                    tools.schemas.subagentSchemas(
                        allowMutation = true,
                        allowVirtualScreen = false,
                        enabledOptional = activeOptional,
                    ),
                ).toSet()
                val missing = grantedExtensions - exposed
                require(missing.isEmpty()) {
                    "TEAM_EXTENSION_NOT_EXPOSED：已授权工具未进入成员实际工具表（数量或上下文预算限制）：" +
                        missing.sorted().joinToString("、")
                }
                require("skill" !in tools.registry.names() || "skill" in exposed) {
                    "TEAM_SKILL_NOT_EXPOSED：技能工具未进入成员实际工具表"
                }
                persistentJobs.startReadonlySubagentResult(
                    task = task,
                    model = model,
                    maxSteps = maxSteps,
                    instructions = instructions,
                    virtualScreen = false,
                    forkParentCallId =
                        parentCallId.takeIf { context == LocalTeamMemberContext.FORK },
                    sessionId = binding.sessionId,
                    boundState = binding.aggregateSnapshot(),
                    historySnapshot = binding.runHandle.modelHistory::snapshot,
                    requestedJobId = requestedJobId,
                    allowMutation = true,
                    teamManaged = true,
                    // 基础网络、后台任务与技能可用；敏感扩展只能由 Lead 按名称授权。
                    initialOptionalTools = activeOptional,
                )
            },
            sendToTeammate = { agentId, input, sessionId ->
                persistentJobs.sendInput(agentId, input, sessionId)
            },
            eventLogFor = sessionStorage.eventLogs::get,
            projectionRegistry = sessionStorage.projectionRegistry,
            projectionScope = scope,
            onProjectionReady = { sessionId -> teamLifecycle.projectionReady(sessionId) },
        )
    }
    private val agentControlBuiltins by lazy {
        LocalWorkAgentControlBuiltinRuntime(
            jobs = runtimeStateStore.jobManager,
            modelGateway = modelGateway,
            subagents = subagents,
            persistentJobs = persistentJobs,
            teams = agentTeams,
        )
    }
    private val turnExecutor = LocalWorkAgentTurnExecutor(
        context = context,
        runtimeStateStore = runtimeStateStore,
        sessionStorage = sessionStorage,
        modelGateway = modelGateway,
        modelRequests = modelRequests,
        usageTracker = usageTracker,
        contextComposer = contextComposer,
        projectContext = projectContext,
        workRunRegistry = workRunRegistry,
        workMemoryRuntime = workMemoryRuntime,
        workModelHistoryRuntime = workModelHistoryRuntime,
        workSessionProjection = workSessionProjection,
        workToolResultRuntime = toolResultRuntime,
        workTurnToolRuntime = turnToolRuntime,
    )

    internal val turnStarter = LocalWorkTurnStarter(
        runtimeStateStore = runtimeStateStore,
        sessionStorage = sessionStorage,
        workRunRegistry = workRunRegistry,
        runTurn = turnExecutor::run,
    )

    private val teamUiProjection by lazy { LocalWorkTeamUiProjection(agentTeams, workRunRegistry, runtimeStateStore) }
    internal val agentUi: LocalWorkAgentUiPort by lazy {
        LocalWorkAgentController(runtimeStateStore, persistentJobs, agentTeams, teamUiProjection)
    }
    private val teamLifecycle by lazy { LocalWorkTeamLifecycle(runtimeStateStore, agentTeams, persistentJobs, teamUiProjection, scope) }

    init { teamLifecycle }

    internal suspend fun executeBuiltin(
        call: LocalToolCall,
        allowMutation: Boolean,
        binding: LocalWorkRunBinding?,
    ): String? {
        taskBuiltins.execute(call, binding)?.let { return it }
        return agentControlBuiltins.execute(call, allowMutation, binding)
    }

    internal fun automationRunner(
        sessionId: String,
        boundState: com.labteto.dshmobile.local.LocalHarnessState,
        onApprovalBlocked: (String) -> Unit,
    ): com.labteto.dshmobile.local.work.LocalSubagentRunner = subagentFactory.createBound(
        sessionId = sessionId,
        boundState = boundState,
        runKind = LocalAgentRunKind.AUTOMATION,
        schemasProvider = { allowMutation, allowVirtualScreen, enabledOptional ->
            tools.schemas.subagentSchemas(
                allowMutation = allowMutation,
                allowVirtualScreen = allowVirtualScreen,
                enabledOptional = enabledOptional,
            )
        },
        executeTool = { call, allowMutation, memoryTools, enabledOptional ->
            backgroundTools.executeAutomationSubagentTool(
                call = call,
                allowMutation = allowMutation,
                sessionId = sessionId,
                memoryTools = memoryTools,
                enabledOptionalTools = enabledOptional,
                onApprovalBlocked = onApprovalBlocked,
            )
        },
        modelAdmission = LocalWorkExecutionControl().asModelAdmissionPort(),
    )

    internal fun schedulePersistentRecovery() {
        val sessionId = runtimeStateStore.currentSessionId
        runCatching { agentTeams.recoverMailbox(sessionId) }
            .onFailure { error ->
                AppLog.warn(
                    "LocalWorkComposition",
                    "Agent Team 启动恢复失败 session=$sessionId",
                    error,
                )
            }
        runCatching { teamUiProjection.publish(sessionId) }
            .onFailure { error ->
                AppLog.warn(
                    "LocalWorkComposition",
                    "Agent Team 启动投影刷新失败 session=$sessionId",
                    error,
                )
            }
        persistentJobs.schedule(sessionId)
    }

    private companion object {
        val TEAM_RECONCILE_JOB_STATUSES = setOf(
            "dormant",
            "completed",
            "failed",
            "cancelled",
            "killed",
        )
    }

}
