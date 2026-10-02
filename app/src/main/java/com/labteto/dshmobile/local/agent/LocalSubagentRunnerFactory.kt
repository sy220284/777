package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.capability.HarnessVirtualDisplayProvider
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.context.ContextComposer
import com.labteto.dshmobile.local.context.ContextRequest
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryStore
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray

/**
 * Centralizes LocalSubagentRunner construction so execution wiring is not repeated inside the engine.
 */
internal class LocalSubagentRunnerFactory(
    private val modelGateway: LocalModelGateway,
    private val jobs: LocalJobManager,
    private val modelHistory: LocalModelHistoryBuffer,
    private val toolOutputStore: LocalToolOutputStore,
    private val workspacePath: String,
    private val imageRequestBudget: LocalImageRequestBudget,
    private val imageCapabilities: LocalImageCapabilityRegistry,
    private val usageTracker: DeepSeekUsageTracker,
    private val resourceScheduler: HarnessResourceScheduler,
    private val virtualDisplayProvider: HarnessVirtualDisplayProvider,
    private val memoryClassMb: Int,
    private val agentRunCoordinator: LocalAgentRunCoordinator,
    private val contextComposer: ContextComposer,
    private val memoryStore: MemoryStore,
    private val memoryManager: MemoryManager,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val defaultState: StateFlow<LocalHarnessState>,
    private val defaultSessionId: () -> String,
) {
    fun createBound(
        sessionId: String,
        boundState: LocalHarnessState,
        runKind: LocalAgentRunKind,
        schemasProvider: (Boolean, Boolean, Set<String>) -> JsonArray,
        executeTool: suspend (
            call: LocalToolCall,
            allowMutation: Boolean,
            memoryTools: LocalMemoryTools,
            enabledOptionalTools: MutableSet<String>,
        ) -> AgentToolResult,
        historySnapshot: () -> List<kotlinx.serialization.json.JsonObject> = modelHistory::snapshot,
        executionBudget: LocalWorkExecutionBudget? = null,
        routeCircuitBreaker: LocalModelRouteCircuitBreaker? = null,
    ): LocalSubagentRunner {
        val boundEventLog = eventLogFor(sessionId)
        val boundMemoryTools = LocalMemoryTools(
            memoryStore,
            memoryManager,
            state = { boundState },
            sessionId = { sessionId },
        )
        return create(
            eventLogProvider = { boundEventLog },
            contextSnapshotProvider = { query ->
                contextComposer.compose(
                    ContextRequest(
                        query = query,
                        mode = boundState.conversationMode,
                        projectId = boundState.projectId,
                        lineageId = boundState.lineageId,
                        handoffSummary = boundState.handoffSummary,
                    ),
                )
            },
            schemasProvider = { allowMutation, allowVirtualScreen, enabledOptional ->
                schemasProvider(allowMutation, allowVirtualScreen, enabledOptional.toSet())
            },
            executeTool = { call, allowMutation, enabledOptional ->
                executeTool(call, allowMutation, boundMemoryTools, enabledOptional)
            },
            runnerState = MutableStateFlow(boundState),
            toolOutputSessionId = { sessionId },
            runSessionId = { sessionId },
            runKind = runKind,
            historySnapshotProvider = historySnapshot,
            executionBudget = executionBudget,
            routeCircuitBreaker = routeCircuitBreaker,
        )
    }

    fun create(
        eventLogProvider: () -> LocalSessionEventLog,
        contextSnapshotProvider: (String) -> String,
        schemasProvider: (Boolean, Boolean, MutableSet<String>) -> JsonArray,
        executeTool: suspend (LocalToolCall, Boolean, MutableSet<String>) -> AgentToolResult,
        runnerState: StateFlow<LocalHarnessState> = defaultState,
        toolOutputSessionId: () -> String = defaultSessionId,
        runSessionId: () -> String = defaultSessionId,
        runKind: LocalAgentRunKind = LocalAgentRunKind.SUBAGENT,
        historySnapshotProvider: () -> List<kotlinx.serialization.json.JsonObject> = modelHistory::snapshot,
        executionBudget: LocalWorkExecutionBudget? = null,
        routeCircuitBreaker: LocalModelRouteCircuitBreaker? = null,
    ): LocalSubagentRunner = LocalSubagentRunner(
        modelGateway = modelGateway,
        state = runnerState,
        jobs = jobs,
        historySnapshot = historySnapshotProvider,
        contextSnapshot = contextSnapshotProvider,
        eventLog = eventLogProvider,
        schemas = schemasProvider,
        execute = executeTool,
        spillToolOutput = { callId, output -> toolOutputStore.store(toolOutputSessionId(), callId, output) != null },
        prepareMessages = { messages, mode, baseUrl, model ->
            prepareLocalMultimodalMessages(
                messages = messages,
                workspaceRoot = File(workspacePath),
                mode = mode,
                budget = imageRequestBudget,
                maxImageBytes = LocalModelPresets.maxNativeImageBytesFor(model, baseUrl),
            )
        },
        resolveImageMode = { mode, baseUrl, model -> resolveLocalImageInputMode(mode, imageCapabilities, baseUrl, model) },
        onNativeImageAccepted = { baseUrl, model -> imageCapabilities.markSupported(baseUrl, model) },
        onUsage = { model, reply, context -> usageTracker.record(model, reply, context) },
        onNativeImageRejected = { baseUrl, model -> imageCapabilities.markUnsupported(baseUrl, model) },
        resourceScheduler = resourceScheduler,
        acquireVirtualScreen = { owner -> virtualDisplayProvider.acquireAgentVirtualDisplay(owner) },
        releaseVirtualScreen = virtualDisplayProvider::releaseAgentVirtualDisplay,
        historyBudget = { baseUrl, model ->
            localHistoryBudgetFor(
                memoryClassMb = memoryClassMb,
                pressure = resourceScheduler.snapshot().pressure,
                model = model,
                baseUrl = baseUrl,
            )
        },
        runCoordinator = agentRunCoordinator,
        runSessionId = runSessionId,
        runKind = runKind,
        executionBudget = executionBudget,
        routeCircuitBreaker = routeCircuitBreaker,
    )
}