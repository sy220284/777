package com.labteto.dshmobile.local.work

import android.content.Context
import com.labteto.dshmobile.harness.agent.AgentEvent
import com.labteto.dshmobile.harness.agent.AgentEventSink
import com.labteto.dshmobile.harness.agent.AgentLoop
import com.labteto.dshmobile.harness.agent.AgentModel
import com.labteto.dshmobile.harness.agent.AgentModelReply
import com.labteto.dshmobile.harness.agent.AgentToolBatchExecutor
import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.agent.AgentToolExecutor
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.modelVisibleContent
import com.labteto.dshmobile.harness.session.SessionRecovery
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelRequestCoordinator
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.agent.localAgentRunPolicy
import com.labteto.dshmobile.local.context.ContextComposer
import com.labteto.dshmobile.local.project.ProjectContextPort
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalImageCapability
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.model.durableToolResultContent
import com.labteto.dshmobile.local.model.estimateModelTokens
import com.labteto.dshmobile.local.model.hasLocalImageRefs
import com.labteto.dshmobile.local.model.hasMaterializedImageUrls
import com.labteto.dshmobile.local.model.imageInputUnsupported
import com.labteto.dshmobile.local.model.localImageRequestBudgetForModelConcurrency
import com.labteto.dshmobile.local.model.localToolHistoryMessage
import com.labteto.dshmobile.local.model.prepareLocalMultimodalMessages
import com.labteto.dshmobile.local.recordForeground
import com.labteto.dshmobile.local.model.resolveLocalImageInputMode
import com.labteto.dshmobile.local.model.toRunModelSurface
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.local.model.withModelToolCallEventData
import com.labteto.dshmobile.local.runtime.FOREGROUND_TURN_TIMEOUT_MILLIS
import com.labteto.dshmobile.local.runtime.LocalAgentProgressTracker
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
import com.labteto.dshmobile.local.runtime.LocalAgentRunResourceBudget
import com.labteto.dshmobile.local.runtime.LocalExecutionService
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.MAX_EVENT_CHARS
import com.labteto.dshmobile.local.runtime.adaptiveAgentStepLimit
import com.labteto.dshmobile.local.runtime.localForegroundStepLimitExtender
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import com.labteto.dshmobile.local.tools.LocalModelToolStepSurface
import com.labteto.dshmobile.local.tools.LocalRunToolSurface
import com.labteto.dshmobile.local.tools.pendingToolSettlements
import com.labteto.dshmobile.local.tools.startedToolCallIdsForActiveStep
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * WorkFeature owner of the foreground AgentLoop for one bound Work run.
 *
 * Session/run identity and resources stay Shared; Work owns prompt composition, tool policy,
 * history/inbox transactions, completion truthfulness, continuation and run teardown.
 */
internal class LocalWorkAgentTurnExecutor(
    private val context: Context,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val modelGateway: LocalModelGateway,
    private val modelRequests: LocalModelRequestCoordinator,
    private val usageTracker: DeepSeekUsageTracker,
    private val contextComposer: ContextComposer,
    private val projectContext: ProjectContextPort,
    private val workRunRegistry: LocalWorkRunRegistry,
    private val workMemoryRuntime: LocalWorkMemoryRuntime,
    private val workModelHistoryRuntime: LocalWorkModelHistoryRuntime,
    private val workSessionProjection: LocalWorkSessionProjectionRuntime,
    private val workToolResultRuntime: LocalWorkToolResultRuntime,
    private val workTurnToolRuntime: LocalWorkTurnToolRuntime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resourceScheduler
        get() = runtimeStateStore.resourceScheduler
    private val agentRunCoordinator
        get() = sessionStorage.agentRunCoordinator
    private val imageCapabilities
        get() = runtimeStateStore.imageCapabilities
    private val imageRequestBudget by lazy {
        localImageRequestBudgetForModelConcurrency(runtimeStateStore.resourceBudget.maxModelRequests)
    }
    private val workspacePath: String
        get() = sessionStorage.files.workspace.path

    private class BoundRunState(
        private val binding: LocalWorkRunBinding,
    ) {
        /** Read-only compatibility snapshot for Shared request APIs; Work writes stay domain-owned. */
        val value: LocalHarnessState
            get() = binding.aggregateSnapshot()
    }

    internal suspend fun run(
        input: String,
        memoryInput: String = input,
        sourceMessageId: String? = null,
        binding: LocalWorkRunBinding,
        preownedLease: LocalSessionRuntimeLease? = null,
    ) {
        val ownerJob = currentCoroutineContext()[Job]
        var heldSession: String? = null
        var serviceReleased = false
        try {
            LocalSessionRuntimeRegistry.withOwner(
                binding.sessionId,
                LocalSessionRuntimeKind.FOREGROUND,
                preownedLease,
            ) {
            val runState = BoundRunState(binding)
            val runEventLog = binding.eventLog
            val runHistory = binding.runHandle.modelHistory
            val runTranscript = binding.transcriptRuntime
            val runSessionId = binding.sessionId
            check(runState.value.usageMode == LocalUsageMode.WORK) {
                "Work Agent 回合只能处理 Work 模式"
            }
            // A new user turn or checkpoint continuation starts a new request slice.
            // When child requests remain in flight the shared budget stays intact.
            binding.executionControl.budget.beginExecutionSlice()
            val runPolicy = localAgentRunPolicy(LocalUsageMode.WORK)
            workTurnToolRuntime.clear(binding)
            workTurnToolRuntime.prepare(binding, input)
            val foregroundSessionId = runSessionId
            var foregroundOutcome = LocalExecutionService.OUTCOME_COMPLETED
            heldSession = foregroundSessionId
            if (!LocalExecutionService.holdTurn(context, foregroundSessionId)) {
                binding.state.update { it.copy(error = "前台执行服务启动失败，当前 Work 回合已停止") }
                error("前台执行服务启动失败")
            }
            binding.state.update {
                it.copy(
                    work = it.work.copy(
                        workflowProgress = null,
                        deviceApprovalLease = false,
                    ),
                    kernel = it.kernel.copy(running = true),
                    error = null,
                )
            }
            val repliesByStep = mutableMapOf<Int, LocalModelReply>()
            var modelStep = 0
            val modelToolStepSurface = LocalModelToolStepSurface()
            var requestPrepared = false
            var workPromptContext = LocalWorkTurnPromptContext()
            val runSnapshot = runState.value
            val runToolSurface = LocalRunToolSurface(
                runSnapshot.modelState.modelSelection.activeProfile?.toRunModelSurface(),
            )
            val mainMaxSteps = runSnapshot.mainMaxSteps
            val mainStepLimit = if (runPolicy.allowToolExecution) {
                adaptiveAgentStepLimit(
                    configuredBase = mainMaxSteps,
                    task = input,
                    contextChars = runSnapshot.kernel.contextChars,
                    contextBudgetChars = runSnapshot.kernel.contextBudgetChars,
                    pressure = resourceScheduler.snapshot().pressure,
                    kind = LocalAgentRunKind.FOREGROUND,
                )
            } else 1
            val continuationParentRunId = binding.continuationParentRunId
            binding.continuationParentRunId = null
            val runContext = agentRunCoordinator.start(
                sessionId = foregroundSessionId,
                usageMode = runSnapshot.usageMode,
                model = runSnapshot.modelState.model,
                baseUrl = runSnapshot.modelState.baseUrl,
                routeProfile = runSnapshot.modelState.modelSelection.activeProfile,
                planMode = runSnapshot.work.planMode,
                policy = runPolicy,
                safeAutoApprovalEnabled = runSnapshot.safeAutoApprovalEnabled,
                maxSteps = mainStepLimit,
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
                toolNames = workTurnToolRuntime.names(runPolicy, binding),
                contextChars = runSnapshot.kernel.contextChars,
                parentRunId = continuationParentRunId,
            )
            var lastModelError: LocalModelException? = null
            val progressTracker = LocalAgentProgressTracker()
            var activeStep: Int? = null
            var activeToolCalls = emptyList<AgentToolCall>()
            val completedToolCallIds = linkedSetOf<String>()

            fun settlePendingTools(reason: String) {
                val actuallyStarted = startedToolCallIdsForActiveStep(runEventLog, activeToolCalls)
                val settlements = pendingToolSettlements(
                    calls = activeToolCalls,
                    startedCallIds = actuallyStarted,
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
                    runEventLog.append("tool/result", buildJsonObject {
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
                    runHistory.append(
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
                    agentRunCoordinator.ensureCurrentOwner(runContext)
                    // Persistent history stays compact; user rules, recalled memory and handoff are
                    // assembled per request and are deliberately never written back into runHistory.
                    if (!requestPrepared) {
                        workModelHistoryRuntime.ensureSystemMessage(binding)
                        val snapshot = runState.value
                        workPromptContext = contextComposer.composeWorkTurnContext(
                            input = input,
                            snapshot = snapshot,
                            workspacePath = workspacePath,
                            projectInstructions = projectContext.instructionsFor(snapshot.projectId),
                            skillGuidance = resolveLocalWorkSkillGuidance(sessionStorage.files.workspace, input),
                        )
                        workMemoryRuntime.captureAutoMemoryDirective(memoryInput, sourceMessageId, binding)
                        requestPrepared = true
                    }
                    workModelHistoryRuntime.drainPendingInputs(binding)
                    val snapshot = runState.value
                    val tools = modelToolStepSurface.capture(
                        runToolSurface.next(workTurnToolRuntime.schemas(runPolicy, binding)),
                    )
                    val productContextTokens =
                        estimateModelTokens(workPromptContext.stable) +
                            estimateModelTokens(workPromptContext.dynamic) +
                            estimateModelTokens(workPromptContext.skill)
                    if (shouldProactivelyCompactBeforeModelStep(snapshot.usageMode, modelStep)) {
                        workModelHistoryRuntime.compactIfNeeded(
                            binding = binding,
                            extraTokens = productContextTokens + estimateModelTokens(tools.toString()),
                        )
                    }
                    val durableRequestMessages = withWorkTurnContext(
                        withLocalWorkExecutionMode(runHistory.snapshot()),
                        workPromptContext.stable,
                        workPromptContext.dynamic,
                        workPromptContext.skill,
                    )
                    val selectedMode = resolveLocalImageInputMode(
                        snapshot.modelState.imageInputMode,
                        imageCapabilities,
                        snapshot.modelState.baseUrl,
                        snapshot.modelState.model,
                    )
                    if (hasLocalImageRefs(durableRequestMessages) &&
                        imageCapabilities.state(snapshot.modelState.baseUrl, snapshot.modelState.model) == LocalImageCapability.UNSUPPORTED) {
                        throw IllegalStateException("当前模型不支持图片理解，请切换支持图片的模型后重试。")
                    }
                    val requestMessages = prepareLocalMultimodalMessages(
                        messages = durableRequestMessages,
                        workspaceRoot = File(workspacePath),
                        mode = selectedMode,
                        budget = imageRequestBudget,
                        maxImageBytes = LocalModelPresets.maxNativeImageBytesFor(snapshot.modelState.model, snapshot.modelState.baseUrl),
                    )
                    val nativeImagesSent = hasMaterializedImageUrls(requestMessages)
                    agentRunCoordinator.ensureCurrentOwner(runContext)
                    val rawReply = try {
                        modelRequests.complete(
                            snapshot = snapshot,
                            messages = requestMessages,
                            step = modelStep + 1,
                            options = com.labteto.dshmobile.local.LocalModelRequestOptions(
                                toolsOverride = tools,
                                publishPreviewEnabled = true,
                                persistOverflowHistory = true,
                                requestLog = runEventLog,
                                previewGuard = {
                                runtimeStateStore.currentSessionId == binding.sessionId &&
                                    runtimeStateStore.state.value.sessionId == binding.sessionId
                            },
                                overflowPersister = { overflowSnapshot, mode ->
                                workModelHistoryRuntime.persistOverflowCompaction(
                                    overflowSnapshot,
                                    mode,
                                    binding,
                                )
                            },
                                contextPolicy = LocalWorkRequestContextPolicy(structuredWorkState(snapshot, runEventLog, workSessionProjection)),
                                admission = binding.executionControl.asModelAdmissionPort(),
                            ),
                        ).also {
                            if (nativeImagesSent) {
                                imageCapabilities.markSupported(snapshot.modelState.baseUrl, snapshot.modelState.model)
                            }
                        }
                    } catch (error: Throwable) {
                        if (error is LocalModelException) lastModelError = error
                        val nativeImageRejected =
                            nativeImagesSent &&
                                imageInputUnsupported(error)
                        if (nativeImageRejected) {
                            imageCapabilities.markUnsupported(snapshot.modelState.baseUrl, snapshot.modelState.model)
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
                    val reply = rawReply.also { completed ->
                        usageTracker.recordForeground(
                            snapshot = snapshot,
                            reply = completed,
                            action = TokenUsageAction.WORK_MAIN,
                            turnId = sourceMessageId ?: runContext.runId,
                            runId = runContext.runId,
                            taskLabel = input,
                            step = modelStep + 1,
                        )
                    }
                    val deliveryReply = guardWorkCompletionDelivery(reply, runState.value, runEventLog)
                    modelStep += 1
                    repliesByStep[modelStep] = deliveryReply
                    AgentModelReply(
                        content = deliveryReply.content.orEmpty(),
                        toolCalls = if (runPolicy.allowToolExecution) {
                            deliveryReply.toolCalls.map { call ->
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
                    agentRunCoordinator.ensureCurrentOwner(runContext)
                    if (!modelToolStepSurface.allows(call.name)) {
                        modelToolStepSurface.hiddenCallResult(call.name)
                    } else {
                        workTurnToolRuntime.execute(
                            binding = binding,
                            call = call.toLocalToolCall(),
                            allowMutation = true,
                        )
                    }
                },
                toolBatch = AgentToolBatchExecutor { calls ->
                    agentRunCoordinator.ensureCurrentOwner(runContext)
                    workTurnToolRuntime.executeBatch(
                        binding = binding,
                        calls = calls.map { it.toLocalToolCall() },
                        allowMutation = true,
                    ).map { (_, result) -> result }
                },
                isParallelTool = { call ->
                    modelToolStepSurface.allows(call.name) &&
                        workTurnToolRuntime.isParallel(call.name)
                },
                eventSink = AgentEventSink { event ->
                    agentRunCoordinator.ensureCurrentOwner(runContext)
                    when (event) {
                        is AgentEvent.TurnStarted -> {
                            runEventLog.append("turn/start", buildJsonObject {
                                put("model", runState.value.modelState.model)
                            })
                        }
                        is AgentEvent.StepStarted -> {
                            activeStep = event.step
                            check(LocalExecutionService.holdTurn(context, foregroundSessionId, event.step)) { "前台执行保护更新失败" }
                            activeToolCalls = emptyList()
                            completedToolCallIds.clear()
                            runEventLog.append("step/start", buildJsonObject {
                                put("step", event.step)
                            })
                        }
                        is AgentEvent.AssistantObserved -> {
                            val reply = repliesByStep.remove(event.step)
                                ?: error("缺少第 ${event.step} 步模型响应")
                            progressTracker.recordAssistant(reply.content.orEmpty(), reply.toolCalls.size)
                            val beforeAssistant = runState.value
                            val transcriptMessages = buildList {
                                reply.reasoning?.takeIf(String::isNotBlank)?.let { reasoning ->
                                    add(runTranscript.newMessage("reasoning", reasoning))
                                }
                                reply.content?.takeIf(String::isNotBlank)?.let { content ->
                                    add(
                                        runTranscript.newMessage(
                                            role = if (event.toolCalls.isEmpty()) "assistant" else "progress",
                                            content = content,
                                        ),
                                    )
                                }
                            }
                            activeToolCalls = event.toolCalls
                            completedToolCallIds.clear()
                            val assistantEvent = runEventLog.append(
                                "assistant/message", runTranscript.withTranscript(reply.message, transcriptMessages)
                                    .withModelToolCallEventData(reply.toolCalls),
                            )
                            runHistory.append(reply.message)
                            workModelHistoryRuntime.updateContextMetrics(binding)
                            runTranscript.applyMessages(transcriptMessages, assistantEvent.sequence)
                            workModelHistoryRuntime.persist(binding)
                        }
                        is AgentEvent.ToolStarted -> {
                            runEventLog.append("tool/call", buildJsonObject {
                                put("step", event.step)
                                put("id", event.call.id)
                                put("name", event.call.name)
                                put("arguments", event.call.arguments)
                                put("execution_started", false)
                            })
                        }
                        is AgentEvent.ToolFinished -> {
                            progressTracker.recordToolResult(event.call, event.output, event.isError)
                            val boundedContent = workToolResultRuntime.retain(
                                binding = binding,
                                callId = event.call.id,
                                result = event.output,
                                retention = event.retention,
                            )
                            val modelOutput = AgentToolResult(
                                content = boundedContent,
                                isError = event.isError,
                                errorCode = event.errorCode,
                                retryable = event.retryable,
                                sideEffect = event.sideEffect,
                                recoveryHint = event.recoveryHint,
                            ).modelVisibleContent()
                            val transcriptMessage = runTranscript.newMessage(
                                role = "tool",
                                content = durableToolResultContent(boundedContent, event.retention),
                                toolName = event.call.name,
                                contentAlreadyBounded = true,
                                toolIsError = event.isError,
                                toolErrorCode = event.errorCode,
                            )
                            val toolEvent = runEventLog.append("tool/result", buildJsonObject {
                                put("step", event.step)
                                put("id", event.call.id)
                                put("name", event.call.name)
                                put(
                                    "content",
                                    truncateWithoutSplittingSurrogatePair(
                                        durableToolResultContent(event.output, event.retention), MAX_EVENT_CHARS,
                                    ),
                                )
                                put("model_content", durableToolResultContent(modelOutput, event.retention))
                                put("is_error", event.isError)
                                put("retention", event.retention.name.lowercase())
                                event.errorCode?.let { put("error_code", it) }
                                put("retryable", event.retryable)
                                put("side_effect", event.sideEffect.name.lowercase())
                                event.recoveryHint?.let { put("recovery_hint", it) }
                                put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                            })
                            runHistory.append(localToolHistoryMessage(event.call.id, modelOutput, event.retention))
                            completedToolCallIds += event.call.id
                            workModelHistoryRuntime.updateContextMetrics(binding)
                            runTranscript.applyMessages(listOf(transcriptMessage), toolEvent.sequence)
                            workModelHistoryRuntime.persist(binding)
                        }
                        is AgentEvent.StepFinished -> {
                            runEventLog.append("step/end", buildJsonObject {
                                put("step", event.step)
                            })
                            activeStep = null
                            activeToolCalls = emptyList()
                            completedToolCallIds.clear()
                        }
                        is AgentEvent.TurnCompleted -> {
                            recordWorkCompletionQuality(event.answer, runState.value, runEventLog)
                            runEventLog.append("turn/end", buildJsonObject {
                                put("reason", "completed")
                                put("steps", event.steps)
                                put("messages", runState.value.transcriptIndex.totalMessageCount)
                            })
                            workModelHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/completed")
                        }
                        is AgentEvent.TurnStepLimit -> {
                            val transcriptMessage = runTranscript.newMessage(
                                "system",
                                "当前任务已无法继续扩展执行预算，已在第 ${event.steps} 步暂停；已有进度已保留。",
                            )
                            val turnEnd = runEventLog.append("turn/end", buildJsonObject {
                                put("reason", "step_limit")
                                put("steps", event.steps)
                                put("messages", runState.value.transcriptIndex.totalMessageCount + 1L)
                                put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                            })
                            runTranscript.applyMessages(listOf(transcriptMessage), turnEnd.sequence)
                            workModelHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/step-limit")
                            workModelHistoryRuntime.persist(binding)
                        }
                        is AgentEvent.TurnFailed -> {
                            settlePendingTools("failed")
                            val detail = event.reason.take(2_000)
                            val continuationEligible =
                                shouldAutoContinueWorkFailure(
                                    error = lastModelError,
                                    automaticContinuationCount = binding.automaticContinuationCount,
                                    pendingInputs = binding.runHandle.pendingInputs.size(),
                                )
                            if (continuationEligible) {
                                runEventLog.append("turn/end", buildJsonObject {
                                    put("reason", if (lastModelError?.code == "WORK_BUDGET_EXHAUSTED") {
                                        "budget_exhausted_continuation"
                                    } else {
                                        "stream_interrupted_continuation"
                                    })
                                    put("detail", detail)
                                    put("messages", runState.value.transcriptIndex.totalMessageCount)
                                })
                                workModelHistoryRuntime.checkpointAtTurnBoundary(binding,
                                    if (lastModelError?.code == "WORK_BUDGET_EXHAUSTED") "turn/budget-continuation"
                                    else "turn/stream-interrupted")
                            } else {
                                val transcriptMessage = runTranscript.newMessage("system", "执行失败：$detail")
                                val turnEnd = runEventLog.append("turn/end", buildJsonObject {
                                    put("reason", "error")
                                    put("detail", detail)
                                    put("messages", runState.value.transcriptIndex.totalMessageCount + 1L)
                                    put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                                })
                                runTranscript.applyMessages(listOf(transcriptMessage), turnEnd.sequence)
                                workModelHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/failed")
                            }
                            workModelHistoryRuntime.persist(binding)
                        }
                        is AgentEvent.TurnCancelled -> {
                            settlePendingTools("cancelled")
                            val transcriptMessage = runTranscript.newMessage("system", "本轮已停止。")
                            val turnEnd = runEventLog.append("turn/end", buildJsonObject {
                                put("reason", "aborted")
                                put("messages", runState.value.transcriptIndex.totalMessageCount + 1L)
                                put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                            })
                            runTranscript.applyMessages(listOf(transcriptMessage), turnEnd.sequence)
                            workModelHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/cancelled")
                            workModelHistoryRuntime.persist(binding)
                        }
                    }
                    agentRunCoordinator.recordEvent(runContext, event)
                },
                maxSteps = mainStepLimit,
                stepLimitExtender = localForegroundStepLimitExtender(
                    enabled = runPolicy.allowToolExecution,
                    configuredBase = mainMaxSteps,
                    task = input,
                    kernelState = { runState.value.kernel },
                    pressure = { resourceScheduler.snapshot().pressure },
                    onExtended = { runEventLog.append("turn/budget-extended", it) },
                    canExtend = progressTracker::claimExtensionProgress,
                ),
                idFactory = { runContext.runId },
            )

            try {
                withTimeout(FOREGROUND_TURN_TIMEOUT_MILLIS) {
                    modelGateway.withFrozenRoute(
                        profileId = runSnapshot.modelState.modelSelection.activeProfileId,
                        model = runSnapshot.modelState.model,
                        baseUrl = runSnapshot.modelState.baseUrl,
                    ) { loop.run(input) }
                }
            } catch (_: TimeoutCancellationException) {
                foregroundOutcome = LocalExecutionService.OUTCOME_FAILED
                val timeoutError = LocalModelException(
                    code = "WORK_SLICE_TIMEOUT",
                    message = "本轮执行达到 15 分钟切片上限",
                    retryable = false,
                    continuationEligible = true,
                )
                val queued = queueAutomaticWorkContinuation(binding, runContext.runId, timeoutError)
                if (!queued) {
                    binding.state.update { it.copy(error = "本轮执行超过 15 分钟，已暂停并保留已有进度") }
                }
            } catch (_: CancellationException) {
                binding.runHandle.cancellationRequested = true
                foregroundOutcome = LocalExecutionService.OUTCOME_CANCELLED
                // TurnCancelled durably records and projects the visible stop message.
            } catch (error: Exception) {
                foregroundOutcome = LocalExecutionService.OUTCOME_FAILED
                val modelError = error as? LocalModelException
                val queued = if (modelError != null) {
                    queueAutomaticWorkContinuation(binding, runContext.runId, modelError)
                } else {
                    false
                }
                if (!queued) {
                    binding.state.update { it.copy(error = error.message ?: "本机执行失败") }
                }
                // TurnFailed has already settled tool side effects and checkpointed model-visible state.
            } finally {
                binding.interactions.cancelAll()
                binding.state.update {
                    it.copy(
                        work = it.work.copy(
                            pendingApproval = null,
                            pendingQuestion = null,
                            deviceApprovalLease = false,
                        ),
                        kernel = it.kernel.copy(running = false),
                    )
                }
                val completedJob = currentCoroutineContext()[Job]
                try {
                    workModelHistoryRuntime.persist(binding)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    binding.state.update { current -> current.copy(error = "Work 回合已结束，快照保存失败：${error.message}") }
                } finally {
                    try {
                        try { LocalExecutionService.releaseTurn(context, foregroundSessionId, foregroundOutcome) }
                        finally { serviceReleased = true }
                    } finally {
                        workRunRegistry.finishTurn(binding, completedJob) { next, ownedBinding ->
                            scope.launch(start = CoroutineStart.LAZY) {
                                run(
                                    input = next.content,
                                    memoryInput = next.memoryInput,
                                    sourceMessageId = next.id,
                                    binding = ownedBinding,
                                )
                            }
                        }?.start()
                    }
                }
            }
        }
    } finally {
        try {
            if (!serviceReleased) heldSession?.let { sessionId ->
                LocalExecutionService.releaseTurn(context, sessionId, LocalExecutionService.OUTCOME_FAILED)
            }
        } finally {
            if (ownerJob != null) workRunRegistry.releaseCompletedTurn(binding, ownerJob)
        }
    }
}

    private fun AgentToolCall.toLocalToolCall(): LocalToolCall = LocalToolCall(
        id = id,
        name = name,
        arguments = arguments,
        rawArguments = rawArguments,
    )
}
