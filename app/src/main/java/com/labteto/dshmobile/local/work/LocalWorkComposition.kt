package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.observability.AppLog
import android.content.Context
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
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
) : LocalWorkAgentUiPort {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class TeamJobSignal(
        val status: String,
        val updatedAt: Long,
        val pendingMessageCount: Int,
    )
    private val teamJobSignals = ConcurrentHashMap<String, TeamJobSignal>()
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
                        executePersistentSubagentTool(
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
    private val agentTeams by lazy {
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
                ->
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
                )
            },
            sendToTeammate = { agentId, input, sessionId ->
                persistentJobs.sendInput(agentId, input, sessionId)
            },
            eventLogFor = sessionStorage.eventLogs::get,
            projectionRegistry = sessionStorage.projectionRegistry,
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

    private val teamRefreshQueue = LocalTeamRefreshQueue(scope) { sessionId, reconcile ->
        if (reconcile) runCatching { agentTeams.recoverMailbox(sessionId) }
            .onFailure { AppLog.warn("LocalWorkComposition", "Agent Team 后台状态恢复失败 session=$sessionId", it) }
        runCatching { publishTeamUiState(sessionId) }
            .onFailure { AppLog.warn("LocalWorkComposition", "Agent Team UI 投影刷新失败 session=$sessionId", it) }
    }

    init {
        runtimeStateStore.observeJobSnapshots(::observeTeamJobTransitions)
    }

    private fun observeTeamJobTransitions(jobs: List<LocalJobInfo>) {
        val teamJobs = jobs.filter { LocalAgentTeamRuntime.isTeamJobId(it.id) }
        val visibleIds = teamJobs.mapTo(hashSetOf(), LocalJobInfo::id)
        teamJobSignals.keys.toList()
            .filterNot(visibleIds::contains)
            .forEach(teamJobSignals::remove)

        data class SessionRefresh(
            var reconcile: Boolean = false,
        )
        val sessions = linkedMapOf<String, SessionRefresh>()

        teamJobs.forEach { job ->
            val current = TeamJobSignal(
                status = job.status,
                updatedAt = job.updatedAt,
                pendingMessageCount = job.pendingMessageCount,
            )
            val previous = teamJobSignals.put(job.id, current)
            if (previous == current) return@forEach

            val sessionId = job.ownerSessionId ?: return@forEach
            val refresh = sessions.getOrPut(sessionId, ::SessionRefresh)
            if (job.status in TEAM_RECONCILE_JOB_STATUSES &&
                (previous?.status != current.status || previous.pendingMessageCount != current.pendingMessageCount)
            ) {
                refresh.reconcile = true
            }
        }

        sessions.forEach { (sessionId, refresh) ->
            teamRefreshQueue.schedule(sessionId, refresh.reconcile)
        }
    }

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
            executeAutomationSubagentTool(
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
        runCatching { publishTeamUiState(sessionId) }
            .onFailure { error ->
                AppLog.warn(
                    "LocalWorkComposition",
                    "Agent Team 启动投影刷新失败 session=$sessionId",
                    error,
                )
            }
        persistentJobs.schedule(sessionId)
    }

    private fun publishTeamUiState(sessionId: String) {
        val team = agentTeams.uiState(sessionId)
        workRunRegistry[sessionId]?.let { binding ->
            binding.workState.update { current -> current.copy(team = team) }
            workRunRegistry.mirrorVisible(binding)
            return
        }
        val visible = runtimeStateStore.state.value
        if (visible.sessionId == sessionId && visible.usageMode == LocalUsageMode.WORK) {
            runtimeStateStore.projection.projectVisibleWorkRun(
                sessionId = sessionId,
                snapshot = visible.copy(work = visible.work.copy(team = team)),
            )
        }
    }

    override suspend fun startBackgroundAgent(task: String): LocalWorkAgentUiResult =
        launchBackgroundAgent(task, instructions = "")

    override suspend fun startResearchAgent(task: String): LocalWorkAgentUiResult =
        launchBackgroundAgent(task, instructions = LocalResearchAgentPreset.instructions)

    private suspend fun launchBackgroundAgent(task: String, instructions: String): LocalWorkAgentUiResult =
        withContext(Dispatchers.IO) {
            val clean = task.trim()
            if (clean.isEmpty()) {
                return@withContext LocalWorkAgentUiResult(false, "请输入要交给后台子代理的任务")
            }
            val sessionId = runtimeStateStore.currentSessionId
            val snapshot = runtimeStateStore.state.value
            if (snapshot.sessionId != sessionId || snapshot.usageMode != LocalUsageMode.WORK) {
                return@withContext LocalWorkAgentUiResult(false, "请先进入当前工作会话再启动后台子代理")
            }
            try {
                val result = persistentJobs.startReadonlySubagentResult(
                    task = clean,
                    instructions = instructions,
                    model = LocalWorkerModelRouter.resolve(null, snapshot),
                    maxSteps = snapshot.subagentMaxSteps,
                    virtualScreen = false,
                    sessionId = sessionId,
                    boundState = snapshot,
                    historySnapshot = runtimeStateStore.foregroundRunHandle.modelHistory::snapshot,
                )
                LocalWorkAgentUiResult(
                    accepted = result.accepted,
                    message = result.message,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LocalWorkAgentUiResult(
                    false,
                    "后台子代理启动失败：" + (error.message ?: error::class.java.simpleName),
                )
            }
        }

    override suspend fun sendMessage(agentId: String, message: String): LocalWorkAgentUiResult =
        withContext(Dispatchers.IO) {
            val clean = message.trim()
            if (clean.isEmpty()) {
                return@withContext LocalWorkAgentUiResult(false, "请输入要追加给子代理的消息")
            }
            try {
                val admission = persistentJobs.sendInput(
                    agentId = agentId,
                    input = QueuedAgentInput(
                        id = "ui-msg-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16),
                        content = clean,
                        memoryInput = clean,
                    ),
                    sessionId = runtimeStateStore.currentSessionId,
                )
                LocalWorkAgentUiResult(
                    accepted = admission.accepted,
                    message = admission.message,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LocalWorkAgentUiResult(
                    false,
                    "消息发送失败：" + (error.message ?: error::class.java.simpleName),
                )
            }
        }

    override suspend fun sendTeamMessage(
        memberId: String,
        message: String,
    ): LocalWorkAgentUiResult = withContext(Dispatchers.IO) {
        val clean = message.trim()
        if (clean.isEmpty()) {
            return@withContext LocalWorkAgentUiResult(false, "请输入要发送给助手的消息")
        }
        val sessionId = runtimeStateStore.currentSessionId
        try {
            val result = agentTeams.sendUiMessage(sessionId, memberId, clean)
            publishTeamUiState(sessionId)
            LocalWorkAgentUiResult(true, result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalWorkAgentUiResult(
                false,
                "助手消息发送失败：" + (error.message ?: error::class.java.simpleName),
            )
        }
    }

    override suspend fun stopTeamMember(memberId: String): LocalWorkAgentUiResult =
        withContext(Dispatchers.IO) {
            val sessionId = runtimeStateStore.currentSessionId
            try {
                val result = agentTeams.interruptUiMember(sessionId, memberId)
                publishTeamUiState(sessionId)
                LocalWorkAgentUiResult(true, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LocalWorkAgentUiResult(
                    false,
                    "停止助手失败：" + (error.message ?: error::class.java.simpleName),
                )
            }
        }

    override suspend fun stopTeam(): LocalWorkAgentUiResult =
        withContext(Dispatchers.IO) {
            val sessionId = runtimeStateStore.currentSessionId
            try {
                val result = agentTeams.interruptAllUi(sessionId)
                publishTeamUiState(sessionId)
                LocalWorkAgentUiResult(true, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LocalWorkAgentUiResult(
                    false,
                    "停止 Agent 集群失败：" + (error.message ?: error::class.java.simpleName),
                )
            }
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

    private suspend fun executeAutomationSubagentTool(
        call: LocalToolCall,
        allowMutation: Boolean,
        sessionId: String,
        memoryTools: com.labteto.dshmobile.local.memory.LocalMemoryTools,
        enabledOptionalTools: MutableSet<String>,
        onApprovalBlocked: (String) -> Unit,
    ): AgentToolResult {
        val canonical = call.copy(name = LocalToolPolicy.canonical(call.name))
        val normalized = if (
            canonical.name in setOf("bash", "run_shell", "web_fetch") &&
            canonical.arguments["run_in_background"]?.let { value ->
                (value as? JsonPrimitive)?.booleanOrNull == true
            } == true
        ) {
            canonical.copy(
                arguments = JsonObject(
                    canonical.arguments + ("run_in_background" to JsonPrimitive(false)),
                ),
            )
        } else {
            canonical
        }
        val log = sessionStorage.eventLogs.get(sessionId)
        log.append("tool/call", buildJsonObject {
            put("id", normalized.id)
            put("name", normalized.name)
            put("arguments", normalized.arguments)
            put("execution_started", false)
            put("automation", true)
        })
        val result = try {
            when (normalized.name) {
                "capability_search" -> AgentToolResult(
                    tools.execution.searchCapabilities(
                        normalized.arguments.string("query"),
                        enabledOptionalTools,
                    ),
                )
                "memory_search", "memory_list" -> AgentToolResult(
                    memoryTools.execute(normalized.name, normalized.arguments, allowMutation = false),
                )
                "tool_output_read" -> AgentToolResult(
                    tools.toolOutputStore.read(
                        sessionId = sessionId,
                        callId = normalized.arguments.string("call_id"),
                        startByte = normalized.arguments.int("start_byte", 0),
                        maxBytes = normalized.arguments.int(
                            "max_bytes",
                            com.labteto.dshmobile.local.LocalToolOutputStore.DEFAULT_READ_BYTES,
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
            automationToolFailure(
                normalized,
                "AUTOMATION_TOOL_ERROR",
                error.message ?: error::class.java.simpleName,
            )
        }
        log.append("tool/result", buildJsonObject {
            put("id", normalized.id)
            put("name", normalized.name)
            put("content", com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair(result.content, MAX_EVENT_CHARS))
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
        val result = tools.execution.executeScoped(
            original = original,
            sessionId = sessionId,
            allowMutation = allowMutation,
            planModeEnabled = false,
            approval = { call, tool, _ ->
                val approvalMode = approvalPreferences.currentMode()
                if (shouldAutoApproveTool(approvalMode, tool)) {
                    sessionStorage.eventLogs.get(sessionId).append("approval/auto", buildJsonObject {
                        put("tool", call.name)
                        put("access", tool.access.name.lowercase())
                        put("mode", "automation-" + approvalMode.name.lowercase())
                    })
                    true
                } else {
                    val reason = "后台任务需要人工审批：" + tool.name
                    onApprovalBlocked(reason)
                    sessionStorage.eventLogs.get(sessionId).append("approval/blocked", buildJsonObject {
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

    private fun automationToolFailure(
        call: LocalToolCall,
        code: String,
        detail: String,
    ): AgentToolResult {
        val retryable = code in setOf(
            "MODEL_TIMEOUT",
            "MODEL_NETWORK",
            "TASK_CANCELLED",
            "PARALLEL_TASK_ERROR",
        ) || code.startsWith("MODEL_HTTP_5") || code.contains("TIMEOUT") || code.contains("NETWORK")
        val access = tools.registry.get(LocalToolPolicy.canonical(call.name))?.access
        val sideEffect = if (
            access in setOf(
                com.labteto.dshmobile.harness.tools.ToolAccess.WORKSPACE_WRITE,
                com.labteto.dshmobile.harness.tools.ToolAccess.SESSION_WRITE,
                com.labteto.dshmobile.harness.tools.ToolAccess.PROCESS,
                com.labteto.dshmobile.harness.tools.ToolAccess.AGENT_CONTROL,
                com.labteto.dshmobile.harness.tools.ToolAccess.DEVICE,
                com.labteto.dshmobile.harness.tools.ToolAccess.PRIVILEGED,
            )
        ) com.labteto.dshmobile.harness.agent.AgentToolSideEffect.POSSIBLE
        else com.labteto.dshmobile.harness.agent.AgentToolSideEffect.NONE
        val recoveryHint = when {
            sideEffect == com.labteto.dshmobile.harness.agent.AgentToolSideEffect.POSSIBLE ->
                "该调用可能已产生部分副作用；先检查当前状态，再决定是否重试。"
            retryable -> "该错误允许重试；网络类错误可先运行 network_diagnose。"
            else -> "检查参数、权限或前置状态后再选择其他方案。"
        }
        return AgentToolResult(
            content = "[${call.name}][$code] 工具执行失败：$detail\n调用 id：${call.id}",
            isError = true,
            errorCode = code,
            retryable = retryable,
            sideEffect = sideEffect,
            recoveryHint = recoveryHint,
        )
    }

    private suspend fun executePersistentSubagentTool(
        call: LocalToolCall,
        allowMutation: Boolean,
        sessionId: String,
        memoryTools: com.labteto.dshmobile.local.memory.LocalMemoryTools,
        enabledOptionalTools: MutableSet<String>,
    ): AgentToolResult {
        val canonical = call.copy(name = LocalToolPolicy.canonical(call.name))
        return when (canonical.name) {
            "capability_search" -> AgentToolResult(
                tools.execution.searchCapabilities(
                    canonical.arguments.string("query"),
                    enabledOptionalTools,
                ),
            )
            "memory_search", "memory_list" -> AgentToolResult(
                memoryTools.execute(canonical.name, canonical.arguments, allowMutation = false),
            )
            "tool_output_read" -> AgentToolResult(
                tools.toolOutputStore.read(
                    sessionId = sessionId,
                    callId = canonical.arguments.string("call_id"),
                    startByte = canonical.arguments.int("start_byte", 0),
                    maxBytes = canonical.arguments.int(
                        "max_bytes",
                        com.labteto.dshmobile.local.LocalToolOutputStore.DEFAULT_READ_BYTES,
                    ),
                ),
            )
            else -> tools.execution.executeScoped(
                original = canonical,
                sessionId = sessionId,
                allowMutation = allowMutation,
                planModeEnabled = false,
                approval = { normalized, tool, summary ->
                    if (sessionId == runtimeStateStore.currentSessionId) {
                        toolApproval.approve(normalized, tool, summary)
                    } else {
                        shouldAutoApproveTool(approvalPreferences.currentMode(), tool)
                    }
                },
            )
        }
    }
}
