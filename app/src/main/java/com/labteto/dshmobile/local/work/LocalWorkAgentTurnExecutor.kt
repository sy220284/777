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
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalImageCapability
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalEffectiveModelContracts
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.normalizeModelBaseUrl
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
import com.labteto.dshmobile.observability.AppLog
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * WorkFeature owner of the foreground AgentLoop for one bound Work run.
 *
 * Session/run identity and resources stay Shared; Work owns prompt composition, tool policy,
 * history/inbox transactions, completion truthfulness, continuation and run teardown.
 */
internal class LocalWorkAgentTurnExecutor(
    private val context: Context,
    private val pinnedInvocations: com.labteto.dshmobile.local.tools.LocalPinnedInvocationStore,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val modelGateway: LocalModelGateway,
    private val modelRequests: LocalModelRequestCoordinator,
    private val usageTracker: DeepSeekUsageTracker,
    private val contextComposer: ContextComposer,
    private val workRunRegistry: LocalWorkRunRegistry,
    private val workMemoryRuntime: LocalWorkMemoryRuntime,
    private val workModelHistoryRuntime: LocalWorkModelHistoryRuntime,
    private val workSessionProjection: LocalWorkSessionProjectionRuntime,
    private val workToolResultRuntime: LocalWorkToolResultRuntime,
    private val workTurnToolRuntime: LocalWorkTurnToolRuntime,
    private val workUiDocumentPublisher: LocalWorkUiDocumentPublisher,
    private val replyDocuments: com.labteto.dshmobile.local.presentation.document.LocalReplyUiDocumentPublisher,
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
        // Preserve the Work turn's origin even when the live transcript window is compacted.
        val uiSourceMessageId = resolveWorkUiDocumentSource(binding.eventLog, sourceMessageId)
            ?: binding.state.value.messages.lastOrNull { it.role == "user" }?.id
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
            val selectedRoute = runState.value.modelState
            val selectedProfile = selectedRoute.modelSelection.activeProfile?.takeIf {
                it.model == selectedRoute.model &&
                    normalizeModelBaseUrl(it.baseUrl) == normalizeModelBaseUrl(selectedRoute.baseUrl)
            }
            val effectiveModel = if (selectedProfile != null) {
                LocalEffectiveModelContracts.forProfile(selectedProfile, withTools = true)
            } else {
                LocalEffectiveModelContracts.resolve(
                    model = selectedRoute.model,
                    baseUrl = selectedRoute.baseUrl,
                    withTools = true,
                )
            }
            binding.executionControl.budget.beginExecutionSlice(effectiveModel.executionBudget)
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
            val uiDefinitionsByStep = mutableMapOf<Int, String?>()
            var modelStep = 0
            var latestAcceptedSamplingRevision = binding.steeringRevision.value
            val modelToolStepSurface = LocalModelToolStepSurface()
            var requestPrepared = false
            var workPromptContext = LocalWorkTurnPromptContext()
            var projectedSteeringRevision = binding.steeringRevision.value
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
            val taskRunId = com.labteto.dshmobile.local.usage.resolveTokenUsageTaskRunId(runEventLog, runContext.runId)
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
                        verifiedReadOnly = workTurnToolRuntime.isVerifiedReadOnly(settlement.call.name),
                    )
                    runEventLog.append("tool/result", interruptedWorkToolEvent(result, uiSourceMessageId, reason))
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
                            skillGuidance = resolveLocalWorkSkillGuidance(
                                sessionStorage.files.workspace, input,
                                selectedInvocations = pinnedInvocations.selected.value,
                            ),
                        )
                        workMemoryRuntime.captureAutoMemoryDirective(memoryInput, sourceMessageId, binding)
                        requestPrepared = true
                    }
                    while (true) {
                    val observedSteer = binding.steeringRevision.value
                    workModelHistoryRuntime.drainPendingInputs(binding)
                    val snapshot = runState.value
                    if (observedSteer != projectedSteeringRevision) {
                        val effectiveInput = runHistory.snapshot().asReversed()
                            .firstOrNull { (it["role"] as? JsonPrimitive)?.contentOrNull == "user" }
                            ?.get("content")?.let { it as? JsonPrimitive }?.contentOrNull
                            ?.takeIf(String::isNotBlank) ?: input
                        workPromptContext = contextComposer.composeWorkTurnContext(
                            input = effectiveInput,
                            snapshot = snapshot,
                            workspacePath = workspacePath,
                            skillGuidance = resolveLocalWorkSkillGuidance(
                                sessionStorage.files.workspace, effectiveInput,
                                selectedInvocations = pinnedInvocations.selected.value,
                            ),
                        )
                        projectedSteeringRevision = observedSteer
                    }
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
                        workPromptContext.dynamic + "\n" + com.labteto.dshmobile.local.presentation.document.LocalReplyUiDocumentProtocol.instruction,
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
                model = snapshot.modelState.model,
                baseUrl = snapshot.modelState.baseUrl,
                planSharing = snapshot.modelState.modelSelection.activeProfile?.authKind ==
                    com.labteto.dshmobile.local.model.LocalModelAuthKind.CHATGPT_PLAN,
                    )
                    val nativeImagesSent = hasMaterializedImageUrls(requestMessages)
                    agentRunCoordinator.ensureCurrentOwner(runContext)
                    val sampling = try {
                        sampleUntilWorkSteered(binding.steeringRevision, observedSteer) {
                        modelRequests.complete(
                            snapshot = snapshot,
                            messages = requestMessages,
                            step = modelStep + 1,
                            options = com.labteto.dshmobile.local.LocalModelRequestOptions(
                                toolsOverride = tools,
                                publishPreviewEnabled = true,
                                previewDeltaTransformFactory = {
                                    val preview = com.labteto.dshmobile.local.presentation.document.LocalReplyUiDocumentPreview()
                                    preview::append
                                },
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
                    val rawReply = when (sampling) {
                        LocalWorkSamplingOutcome.Steered -> {
                            runEventLog.append("work/model-sampling-steered", buildJsonObject {
                                put("step", modelStep + 1)
                                put("run_id", runContext.runId)
                                put("revision", binding.steeringRevision.value)
                                put("effect", "model_only")
                            })
                            continue
                        }
                        is LocalWorkSamplingOutcome.Completed -> sampling.value
                    }
                    latestAcceptedSamplingRevision = observedSteer
                    val reply = rawReply.also { completed ->
                        usageTracker.recordForeground(
                            snapshot = snapshot,
                            reply = completed,
                            action = TokenUsageAction.WORK_MAIN,
                            turnId = sourceMessageId ?: runContext.runId,
                            runId = runContext.runId,
                            taskRunId = taskRunId,
                            taskLabel = input,
                            step = modelStep + 1,
                        )
                    }
                    val parts = com.labteto.dshmobile.local.presentation.document.LocalReplyUiDocumentProtocol.split(reply.content.orEmpty())
                    val cleanReply = if (parts.text == reply.content) reply else
                        com.labteto.dshmobile.local.model.withLocalReplyContent(reply, parts.text).copy(providerContentForReplay = null)
                    val deliveryReply = guardWorkCompletionDelivery(cleanReply, runState.value, runEventLog, File(workspacePath))
                    modelStep += 1
                    repliesByStep[modelStep] = deliveryReply
                    uiDefinitionsByStep[modelStep] = parts.definition.takeIf { deliveryReply.content == cleanReply.content }
                    return@AgentModel AgentModelReply(
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
                    }
                    error("Unreachable: Work sampling must return or be cancelled")
                },
                tools = AgentToolExecutor { call ->
                    agentRunCoordinator.ensureCurrentOwner(runContext)
                    if (binding.steeringRevision.value != latestAcceptedSamplingRevision) {
                        workSteeredToolResult(call.toLocalToolCall())
                    } else if (!modelToolStepSurface.allows(call.name)) {
                        modelToolStepSurface.hiddenCallResult(call.name)
                    } else {
                        workTurnToolRuntime.execute(
                            binding = binding,
                            call = call.toLocalToolCall(),
                            allowMutation = true,
                            stillCurrent = {
                                binding.steeringRevision.value == latestAcceptedSamplingRevision
                            },
                        )
                    }
                },
                toolBatch = AgentToolBatchExecutor { calls ->
                    agentRunCoordinator.ensureCurrentOwner(runContext)
                    workTurnToolRuntime.executeBatch(
                        binding = binding,
                        calls = calls.map { it.toLocalToolCall() },
                        allowMutation = true,
                        superseded = { binding.steeringRevision.value != latestAcceptedSamplingRevision },
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
                                put("run_id", runContext.runId)
                                put("source_message_id", uiSourceMessageId)
                                put("model", runState.value.modelState.model)
                            })
                            try {
                                workUiDocumentPublisher.onToolResult(binding, uiSourceMessageId)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                AppLog.failure("WorkUiDocument", "publish_progress", failure)
                            }
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
                                "assistant/message", runTranscript.withTranscript(
                                    kotlinx.serialization.json.JsonObject(reply.message + ("source_message_id" to JsonPrimitive(uiSourceMessageId))), transcriptMessages)
                                    .withModelToolCallEventData(reply.toolCalls),
                            )
                            uiSourceMessageId?.let { source ->
                                replyDocuments.publish(uiDefinitionsByStep.remove(event.step),
                                    com.labteto.dshmobile.uicontract.UiDocumentSource(binding.sessionId, source, 0L,
                                        beforeAssistant.modelState.modelSelection.activeProfileId,
                                        beforeAssistant.projectId, true), reply.requestId,
                                    beforeAssistant.modelState.model, reply.usage.reported)
                            }
                            runHistory.append(reply.message)
                            workModelHistoryRuntime.updateContextMetrics(binding)
                            runTranscript.applyMessages(transcriptMessages, assistantEvent.sequence)
                            workModelHistoryRuntime.persist(binding)
                            try {
                                workUiDocumentPublisher.onToolResult(binding, uiSourceMessageId)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                AppLog.failure("WorkUiDocument", "publish_progress", failure)
                            }
                        }
                        is AgentEvent.ToolStarted -> {
                            runEventLog.append("tool/call", buildJsonObject {
                                put("source_message_id", uiSourceMessageId)
                                put("goal_id", binding.workState.snapshot().goal?.id ?: "")
                                put("step", event.step)
                                put("id", event.call.id)
                                put("name", event.call.name)
                                put("arguments", event.call.arguments)
                                put("execution_started", false)
                            })
                            // Pending tool status is durable before the Work card observes it.
                            try {
                                workUiDocumentPublisher.onToolResult(binding, uiSourceMessageId)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                AppLog.failure("WorkUiDocument", "publish_tool_started", failure)
                            }
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
                            val artifactVersion = if (!event.isError) localWorkArtifactFileVersion(
                                java.io.File(sessionStorage.files.workspace.path), event.call.name, event.output,
                            ) else null
                            val toolEvent = runEventLog.append("tool/result", buildJsonObject {
                                put("source_message_id", uiSourceMessageId)
                                event.executionId?.let { put("execution_id", it) }
                                event.processExitCode?.let { put("process_exit_code", it) }
                                put("file_versions", kotlinx.serialization.json.JsonObject(event.fileVersions.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
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
                                artifactVersion?.let {
                                    put("artifact_path", it.path)
                                    put("artifact_sha256", it.sha256)
                                    put("artifact_bytes", it.bytes)
                                }
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
                            // Event is durable before exposing a UI snapshot. A broken card must
                            // never abort a completed tool or alter its execution result.
                            try {
                                workUiDocumentPublisher.onToolResult(binding, uiSourceMessageId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                AppLog.failure("WorkUiDocument", "publish_tool_progress", failure)
                            }
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
                            recordWorkCompletionQuality(event.answer, runState.value, runEventLog, File(workspacePath))
                            runEventLog.append("turn/end", buildJsonObject {
                                    put("source_message_id", uiSourceMessageId)
                                put("reason", "completed")
                                put("steps", event.steps)
                                put("messages", runState.value.transcriptIndex.totalMessageCount)
                            })
                            workModelHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/completed")
                            try {
                                workUiDocumentPublisher.onToolResult(binding, uiSourceMessageId)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                AppLog.failure("WorkUiDocument", "publish_completed_turn", failure)
                            }
                        }
                        is AgentEvent.TurnStepLimit -> {
                            val transcriptMessage = runTranscript.newMessage(
                                "system",
                                "当前任务已无法继续扩展执行预算，已在第 ${event.steps} 步暂停；已有进度已保留。",
                            )
                            val turnEnd = runEventLog.append("turn/end", buildJsonObject {
                                    put("source_message_id", uiSourceMessageId)
                                put("reason", "step_limit")
                                put("steps", event.steps)
                                put("messages", runState.value.transcriptIndex.totalMessageCount + 1L)
                                put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                            })
                            runTranscript.applyMessages(listOf(transcriptMessage), turnEnd.sequence)
                            workModelHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/step-limit")
                            workModelHistoryRuntime.persist(binding)
                            try {
                                workUiDocumentPublisher.onToolResult(binding, uiSourceMessageId)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                AppLog.failure("WorkUiDocument", "publish_step_limit", failure)
                            }
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
                                    put("source_message_id", uiSourceMessageId)
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
                                    put("source_message_id", uiSourceMessageId)
                                    put("reason", "error")
                                    put("detail", detail)
                                    put("messages", runState.value.transcriptIndex.totalMessageCount + 1L)
                                    put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                                })
                                runTranscript.applyMessages(listOf(transcriptMessage), turnEnd.sequence)
                                workModelHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/failed")
                            }
                            workModelHistoryRuntime.persist(binding)
                            try {
                                workUiDocumentPublisher.onToolResult(binding, uiSourceMessageId)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                AppLog.failure("WorkUiDocument", "publish_failed_turn", failure)
                            }
                        }
                        is AgentEvent.TurnCancelled -> {
                            settlePendingTools("cancelled")
                            val transcriptMessage = runTranscript.newMessage("system", "本轮已停止。")
                            val turnEnd = runEventLog.append("turn/end", buildJsonObject {
                                    put("source_message_id", uiSourceMessageId)
                                put("reason", "aborted")
                                put("messages", runState.value.transcriptIndex.totalMessageCount + 1L)
                                put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                            })
                            runTranscript.applyMessages(listOf(transcriptMessage), turnEnd.sequence)
                            workModelHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/cancelled")
                            workModelHistoryRuntime.persist(binding)
                            try {
                                workUiDocumentPublisher.onToolResult(binding, uiSourceMessageId)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                AppLog.failure("WorkUiDocument", "publish_cancelled_turn", failure)
                            }
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
                modelGateway.withFrozenRoute(
                    profileId = runSnapshot.modelState.modelSelection.activeProfileId,
                    model = runSnapshot.modelState.model,
                    baseUrl = runSnapshot.modelState.baseUrl,
                ) { loop.run(input) }
            } catch (timeout: TimeoutCancellationException) {
                // Only an actual nested operation may now time out; foreground work has no wall-clock stop.
                foregroundOutcome = LocalExecutionService.OUTCOME_FAILED
                AppLog.failure("WorkAgentTurn", "foreground_operation_timeout", timeout,
                    sessionId = foregroundSessionId, runId = runContext.runId)
                binding.state.update { it.copy(error = "后台操作超时，已保留当前进度；请查看诊断原因后继续") }
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
                    AppLog.failure("WorkAgentTurn", "persist_work_checkpoint", error,
                        sessionId = foregroundSessionId, runId = runContext.runId)
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
