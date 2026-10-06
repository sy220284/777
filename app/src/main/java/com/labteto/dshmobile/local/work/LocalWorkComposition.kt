package com.labteto.dshmobile.local.work

import android.content.Context
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.local.LocalModelRequestCoordinator
import com.labteto.dshmobile.local.LocalToolApprovalRuntime
import com.labteto.dshmobile.local.LocalToolCompositionRoot
import com.labteto.dshmobile.local.agent.LocalSubagentRunnerFactory
import com.labteto.dshmobile.local.context.ContextComposer
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.jobs.LocalPersistentJobRecoveryCoordinator
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.model.localImageRequestBudgetForModelConcurrency
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
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
import kotlinx.serialization.json.Json

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

    internal fun schedulePersistentRecovery() = persistentJobs.schedule()

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
