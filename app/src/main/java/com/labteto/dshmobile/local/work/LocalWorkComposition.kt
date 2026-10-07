package com.labteto.dshmobile.local.work

import android.content.Context
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.local.LocalModelRequestCoordinator
import com.labteto.dshmobile.local.LocalToolApprovalRuntime
import com.labteto.dshmobile.local.LocalToolCompositionRoot
import com.labteto.dshmobile.local.context.ContextComposer
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
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
import com.labteto.dshmobile.local.tools.LocalToolPolicy
import com.labteto.dshmobile.local.tools.int
import com.labteto.dshmobile.local.tools.string
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
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
    private val agentControlBuiltins by lazy {
        LocalWorkAgentControlBuiltinRuntime(
            jobs = runtimeStateStore.jobManager,
            modelGateway = modelGateway,
            subagents = subagents,
            persistentJobs = persistentJobs,
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

    internal fun schedulePersistentRecovery() = persistentJobs.schedule()

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
                if (approvalPreferences.isSafeAutoApprovalEnabled()) {
                    sessionStorage.eventLogs.get(sessionId).append("approval/auto", buildJsonObject {
                        put("tool", call.name)
                        put("access", tool.access.name.lowercase())
                        put("mode", "automation-global")
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
                        approvalPreferences.isSafeAutoApprovalEnabled()
                    }
                },
            )
        }
    }
}
