package com.labteto.dshmobile.local.work

import android.content.Context
import com.labteto.dshmobile.harness.agent.AgentEvent
import com.labteto.dshmobile.harness.agent.AgentEventSink
import com.labteto.dshmobile.harness.agent.AgentTurnEngine
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
import com.labteto.dshmobile.local.work.improvement.LocalWorkImprovementArchive
import com.labteto.dshmobile.local.work.improvement.WorkImprovementAdvice
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalImageCapability
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalEffectiveModelContracts
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.normalizeModelBaseUrl
import com.labteto.dshmobile.local.model.forPersistedSessionModel
import com.labteto.dshmobile.local.model.selectedLocalModelProfileId
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.model.durableToolResultContent
import com.labteto.dshmobile.local.model.estimateModelTokens
import com.labteto.dshmobile.local.model.hasMaterializedImageUrls
import com.labteto.dshmobile.local.model.imageInputUnsupported
import com.labteto.dshmobile.local.model.localImageRequestBudgetForModelConcurrency
import com.labteto.dshmobile.local.model.localToolHistoryMessage
import com.labteto.dshmobile.local.model.prepareLocalMultimodalMessages
import com.labteto.dshmobile.local.recordForeground
import com.labteto.dshmobile.local.model.resolveLocalImageInputMode
import com.labteto.dshmobile.local.model.toRunModelSurface
import com.labteto.dshmobile.local.model.routeFingerprint
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
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * WorkFeature owner of the foreground AgentTurnEngine for one bound Work run.
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
    private val improvementArchive: LocalWorkImprovementArchive,
    private val replyDocuments: com.labteto.dshmobile.local.presentation.document.LocalReplyUiDocumentPublisher,
    private val onTurnSettled: (LocalWorkRunBinding, String, WorkImprovementAdvice?) -> Unit = {},
    private val onRootExecutionSliceStarted: suspend (LocalWorkRunBinding) -> Unit = {},
) {
    init { LocalWorkSummarySettings.attach(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resourceScheduler
        get() = runtimeStateStore.resourceScheduler
    private val agentRunCoordinator
        get() = sessionStorage.agentRunCoordinator
    private val imageCapabilities
        get() = runtimeStateStore.imageCapabilities
    private val imageRequestBudget get() = localImageRequestBudgetForModelConcurrency(runtimeStateStore.resourceScheduler.snapshot().budget.maxModelRequests)
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
        continuation: com.labteto.dshmobile.harness.agent.AgentContinuationRef? = null,
    ) {
        val ownerJob = currentCoroutineContext()[Job]
        // Preserve the Work turn's origin even when the live transcript window is compacted.
        val uiSourceMessageId = workDocumentSourceOrLastUser(
            sourceMessageId, resolveWorkUiDocumentSource(binding.eventLog, sourceMessageId),
            binding.state.value.messages.lastOrNull { it.role == "user" }?.id,
        )
        var foregroundLease: LocalExecutionService.Companion.TurnLease? = null
        var serviceReleased = false
        var cpuProjection: Job? = null
        try {
            LocalSessionRuntimeRegistry.withOwner(
                binding.sessionId,
                LocalSessionRuntimeKind.FOREGROUND,
                preownedLease,
            ) {
            val runState = BoundRunState(binding)
            val runEventLog = binding.eventLog
            // Final Goal fence: a user can change Work after inbox consumption but
            // before this lazy coroutine obtains its Session runtime ownership.
            if (!isCurrentWorkGoalContinuation(continuation, runState.value.work, runEventLog)) {
                runEventLog.append("turn/goal-continuation-stale", buildJsonObject {
                    put("source_run_id", continuation?.sourceRunId ?: "")
                    put("goal_id", continuation?.goalId ?: "")
                    put("reason", "goal_changed_before_model_start")
                })
                return@withOwner
            }
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
            val lease = LocalExecutionService.acquireTurn(context, foregroundSessionId)
            if (lease == null) {
                val message = "系统未准许前台执行，当前 Work 回合未启动；进度已保留"
                runEventLog.append("turn/foreground-start-rejected", buildJsonObject {
                    put("session_id", foregroundSessionId)
                    put("source_message_id", uiSourceMessageId)
                    put("reason", "foreground_service_not_ready")
                })
                binding.state.update { it.copy(error = message, kernel = it.kernel.copy(running = false)) }
                return@withOwner
            }
            foregroundLease = lease
            cpuProjection = CoroutineScope(currentCoroutineContext()).launch {
                workRunCpuDemand(binding).collect { lease.setCpuActive(it) }
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
            val declaredToolEvents = mutableMapOf<String, Pair<Long, Long>>()
            var outputLimitHasProgress = false
            val repliesByStep = mutableMapOf<Int, LocalModelReply>()
            val uiDefinitionsByStep = mutableMapOf<Int, String?>()
            val replyFormHandlingByStep = mutableMapOf<Int,
                com.labteto.dshmobile.local.presentation.document.WorkReplyFormHandling>()
            var waitingForReplyFormSubmission = false
            var modelStep = 0
            var latestAcceptedSamplingRevision = binding.steeringRevision.value
            val modelToolStepSurface = LocalModelToolStepSurface()
            var requestPrepared = false
            var workPromptContext = LocalWorkTurnPromptContext()
            var projectedSteeringRevision = binding.steeringRevision.value
            val runSnapshot = runState.value
            val goalAtTurnStart = runSnapshot.work.goal
            val steeringRevisionAtTurnStart = binding.steeringRevision.value
            var runToolSurface = LocalRunToolSurface(
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
            var hadSuccessfulToolReceipt = false
            var hadToolFailure = false
            var completedGoalDisposition: LocalWorkGoalDisposition? = null
            var approvedPlanPromptPending = false
            var improvementChecked = false
            var selectedImprovement: WorkImprovementAdvice? = null
            var improvementGoalId: String? = null

            fun settlePendingTools(reason: String) {
                val settlements = pendingToolSettlements(
                    calls = activeToolCalls,
                    eventLog = runEventLog,
                    completedCallIds = completedToolCallIds,
                )
                if (settlements.isEmpty()) return
                settlements.forEach { settlement ->
                    val result = SessionRecovery.interruptedToolResult(
                        callId = settlement.call.id,
                        name = settlement.call.name,
                        step = activeStep,
                        started = settlement.started,
                        verifiedReadOnly = settlement.verifiedReadOnly,
                        executionId = settlement.executionId,
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

            var effectiveSkillInput = input
            val loop = AgentTurnEngine(
                model = AgentModel {
                    agentRunCoordinator.ensureCurrentOwner(runContext)
                    // Persistent history stays compact; user rules, recalled memory and handoff are
                    // assembled per request and are deliberately never written back into runHistory.
                    if (!requestPrepared) {
                        workModelHistoryRuntime.repairInterleavedPromptUpdates(binding)
                        workModelHistoryRuntime.ensureSystemMessage(binding)
                        val snapshot = runState.value
                        workPromptContext = contextComposer.composeWorkTurnContext(
                            input = input,
                            snapshot = snapshot,
                            workspacePath = workspacePath,
                            skillGuidance = resolveLocalWorkSkillGuidance(
                                sessionStorage.files.workspace, input,
                                selectedInvocations = pinnedInvocations.current(),
                            ),
                        )
                        if (memoryInput.isNotBlank()) workMemoryRuntime.captureAutoMemoryDirective(memoryInput, sourceMessageId, binding)
                        requestPrepared = true
                    }
                    while (true) {
                    val observedSteer = binding.steeringRevision.value
                    workModelHistoryRuntime.drainPendingInputs(binding)
                    // This callback is entered once per model request, after the previous
                    // request and its tool results have settled. Switching here never mutates
                    // the route of a request already in flight.
                    val beforeModel = binding.state.value.modelState
                    val selectedModel = beforeModel.forPersistedSessionModel(
                        runEventLog.selectedLocalModelProfileId(),
                    )
                    if (selectedModel.modelSelection.activeProfileId !=
                        beforeModel.modelSelection.activeProfileId
                    ) {
                        binding.state.update { current ->
                            current.copy(modelState = current.modelState.forPersistedSessionModel(
                                selectedModel.modelSelection.activeProfileId,
                            ))
                        }
                        runToolSurface = LocalRunToolSurface(
                            selectedModel.modelSelection.activeProfile?.toRunModelSurface(),
                        )
                        workPromptContext = contextComposer.composeWorkTurnContext(
                            input = input,
                            snapshot = runState.value,
                            workspacePath = workspacePath,
                            skillGuidance = resolveLocalWorkSkillGuidance(
                                sessionStorage.files.workspace, input,
                                selectedInvocations = pinnedInvocations.current(),
                            ),
                        )
                        workModelHistoryRuntime.updateContextMetrics(binding)
                    }
                    val snapshot = runState.value
                    if (observedSteer != projectedSteeringRevision) {
                        val effectiveInput = runHistory.snapshot().asReversed()
                            .firstOrNull { (it["role"] as? JsonPrimitive)?.contentOrNull == "user" }
                            ?.get("content")?.let { it as? JsonPrimitive }?.contentOrNull
                            ?.takeIf(String::isNotBlank) ?: input
                        effectiveSkillInput = effectiveInput
                        workPromptContext = contextComposer.composeWorkTurnContext(
                            input = effectiveInput,
                            snapshot = snapshot,
                            workspacePath = workspacePath,
                            skillGuidance = resolveLocalWorkSkillGuidance(
                                sessionStorage.files.workspace, effectiveInput,
                                selectedInvocations = pinnedInvocations.current(),
                            ),
                        )
                        projectedSteeringRevision = observedSteer
                    }
                    // An unverified strategy can be tried only on a naturally
                    // requested Goal with the identical acceptance contract as its verified
                    // source. No duplicate execution, permission change or stable prompt edit.
                    val liveGoal = snapshot.work.goal
                    if (!improvementChecked && liveGoal?.status == "active" && liveGoal.id.isNotBlank()) {
                        improvementChecked = true
                        improvementArchive.adviceFor(
                            liveGoal,
                            snapshot.projectId ?: "",
                            snapshot.modelState.model,
                            snapshot.modelState.modelSelection.activeProfileId ?: "",
                            snapshot.modelState.modelSelection.activeProfile?.routeFingerprint().orEmpty(),
                        )?.let { advice ->
                            selectedImprovement = advice
                            improvementGoalId = liveGoal.id
                            runEventLog.append(
                                if (advice.active) "work/improvement-applied" else "work/improvement-trial",
                                buildJsonObject {
                                    put("candidate_id", advice.id)
                                    put("run_id", runContext.runId)
                                    put("goal_id", liveGoal.id)
                                    put("goal_revision", liveGoal.revision)
                                    put("steering_revision", binding.steeringRevision.value)
                                    put("goal_signature", advice.goalSignature)
                                    put("origin_session", advice.sourceSession)
                                    put("status", if (advice.active) "active" else "experimental")
                                },
                            )
                        }
                    }
                    selectedImprovement?.let { advice ->
                        if (liveGoal?.id == improvementGoalId &&
                            snapshot.modelState.model == advice.model &&
                            (snapshot.modelState.modelSelection.activeProfileId ?: "") == advice.profileId &&
                            snapshot.modelState.modelSelection.activeProfile?.routeFingerprint().orEmpty() == advice.routeFingerprint) {
                            val marker = "[可选工作方法建议：" + advice.id + "]"
                            if (marker !in workPromptContext.dynamic) {
                                workPromptContext = workPromptContext.copy(
                                    dynamic = workPromptContext.dynamic + "\n\n" + marker +
                                        "\n来自历史工作证据的" +
                                        (if (advice.active) "已采纳方法" else "待验证实验候选") +
                                        "；仅作为辅助建议，用户要求与已有权限/验收规则优先。\n" +
                                        advice.guidance,
                                )
                            }
                        }
                    }
                    val skillGuidance = resolveLocalWorkSkillGuidance(
                        sessionStorage.files.workspace, effectiveSkillInput,
                        selectedInvocations = pinnedInvocations.current(),
                    )
                    if (skillGuidance != workPromptContext.skill) {
                        // Replace this request's layer, never append catalog updates to history.
                        workPromptContext = workPromptContext.copy(skill = skillGuidance)
                    }
                    val tools = modelToolStepSurface.capture(
                        runToolSurface.next(workTurnToolRuntime.schemas(runPolicy, binding)),
                    )
                    val productContextTokens =
                        estimateModelTokens(workPromptContext.stable) +
                            estimateModelTokens(workPromptContext.dynamic) +
                            estimateModelTokens(workPromptContext.skill)
                    if (shouldProactivelyCompactBeforeModelStep(snapshot.usageMode, modelStep)) {
                        LocalWorkSummarySettings.attach(context)
                        val extra = productContextTokens + estimateModelTokens(tools.toString())
                        if (LocalWorkSummarySettings.state.value) {
                            val outcome = sampleUntilWorkSteered(binding.steeringRevision, observedSteer) {
                                workModelHistoryRuntime.compactSemanticallyBeforeStep(binding, extra) { request ->
                                    try {
                                        agentRunCoordinator.ensureCurrentOwner(runContext)
                                        val reply = modelRequests.complete(snapshot, request.messages, modelStep + 1,
                                            com.labteto.dshmobile.local.LocalModelRequestOptions(
                                                toolsOverride = kotlinx.serialization.json.JsonArray(emptyList()),
                                                publishPreviewEnabled = false, allowContextOverflowRecovery = false,
                                                requestLog = runEventLog,
                                                profile = snapshot.modelState.modelSelection.activeProfile,
                                                admission = binding.executionControl.asModelAdmissionPort()))
                                        usageTracker.recordForeground(snapshot, reply,
                                            action = TokenUsageAction.WORK_SUMMARY,
                                            turnId = sourceMessageId ?: runContext.runId,
                                            runId = runContext.runId, taskRunId = taskRunId,
                                            taskLabel = "工作历史背景摘要", step = modelStep + 1)
                                        agentRunCoordinator.ensureCurrentOwner(runContext)
                                        com.labteto.dshmobile.local.model.requireCompletedModelReply(reply)
                                        check(reply.toolCalls.isEmpty()) { "摘要不能包含工具调用" }
                                        reply.content
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) {
                                        runEventLog.append("work/semantic-summary-skipped", buildJsonObject {
                                            put("reason", "summary_not_completed"); put("error_type", failure::class.java.simpleName)
                                        })
                                        null
                                    }
                                }
                            }
                            if (outcome is LocalWorkSamplingOutcome.Steered) continue
                            // Optional remote background generation must never disable the existing
                            // local, bounded and evidence-preserving compactor when it produces no
                            // admissible history replacement. Revalidate steering and ownership
                            // before touching the history after the auxiliary provider request.
                            if (outcome is LocalWorkSamplingOutcome.Completed && !outcome.value) {
                                agentRunCoordinator.ensureCurrentOwner(runContext)
                                if (binding.steeringRevision.value != observedSteer) continue
                                workModelHistoryRuntime.compactIfNeeded(binding = binding, extraTokens = extra)
                            }
                        } else {
                            workModelHistoryRuntime.compactIfNeeded(binding = binding, extraTokens = extra)
                        }
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
                    com.labteto.dshmobile.local.model.requireLocalImageInputSupported(
                        durableRequestMessages,
                        unsupported = imageCapabilities.state(snapshot.modelState.baseUrl, snapshot.modelState.model) == LocalImageCapability.UNSUPPORTED,
                    )
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
                    val parts = com.labteto.dshmobile.local.presentation.document.LocalReplyUiDocumentProtocol.split(
                        reply.content.orEmpty(),
                    )
                    val cleanReply = if (reply.completionReason != com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED || parts.text == reply.content) reply else
                        com.labteto.dshmobile.local.model.withLocalReplyContent(reply, parts.text).copy(providerContentForReplay = null)
                    val deliveryReply = guardWorkCompletionDelivery(cleanReply, runState.value, runEventLog, File(workspacePath))
                    modelStep += 1
                    repliesByStep[modelStep] = deliveryReply
                    replyFormHandlingByStep[modelStep] =
                        com.labteto.dshmobile.local.presentation.document.LocalReplyUiDocumentProtocol
                            .workReplyFormHandling(parts.definition, deliveryReply.toolCalls.isNotEmpty())
                    // A form with outstanding tools is not a user-input checkpoint: this
                    // Work turn will keep executing. Publish it only on a tool-free final reply
                    // so users never see a form while the agent is already moving ahead.
                    uiDefinitionsByStep[modelStep] = parts.definition.takeIf {
                        deliveryReply.completionReason == com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED &&
                        deliveryReply.content == cleanReply.content &&
                            com.labteto.dshmobile.local.presentation.document.LocalReplyUiDocumentProtocol
                                .workFormMayBePublished(parts.definition, deliveryReply.toolCalls.size)
                    }
                    return@AgentModel AgentModelReply(
                        completionReason = deliveryReply.completionReason,
                        rawStopReason = deliveryReply.rawStopReason,
                        reportedUsage = com.labteto.dshmobile.local.model.agentReportedModelUsage(deliveryReply.usage),
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
                            onRootExecutionSliceStarted(binding)
                        }
                        is AgentEvent.StepStarted -> {
                            activeStep = event.step
                            check(lease.updateStep(event.step)) { "前台服务已失去执行租约，Work 回合已停止" }
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
                            val formHandling = replyFormHandlingByStep.remove(event.step)
                            val definition = uiDefinitionsByStep.remove(event.step)
                            val formPublished = uiSourceMessageId?.let { source ->
                                replyDocuments.publish(definition,
                                    com.labteto.dshmobile.uicontract.UiDocumentSource(binding.sessionId, source, 0L,
                                        beforeAssistant.modelState.modelSelection.activeProfileId,
                                        beforeAssistant.projectId, true), reply.requestId,
                                    beforeAssistant.modelState.model, reply.usage.reported)
                            } ?: false
                            if (formPublished &&
                                formHandling == com.labteto.dshmobile.local.presentation.document.WorkReplyFormHandling.WAIT_FOR_SUBMISSION
                            ) {
                                waitingForReplyFormSubmission = true
                                runEventLog.append(WORK_REPLY_FORM_WAIT_EVENT, buildJsonObject {
                                    put("source_message_id", uiSourceMessageId)
                                    put("step", event.step)
                                    binding.workState.snapshot().goal?.let { goal ->
                                        put("goal_id", goal.id)
                                        put("goal_revision", goal.revision)
                                    }
                                })
                            }
                            if (reply.completionReason == com.labteto.dshmobile.local.model.LocalModelCompletionReason.OUTPUT_LIMIT) {
                                val priorTexts = runHistory.snapshot().filter {
                                    (it["role"] as? JsonPrimitive)?.contentOrNull == "assistant"
                                }.map { prior ->
                                    com.labteto.dshmobile.local.model.LocalCanonicalModelCodec.message(prior).content
                                        .filterIsInstance<com.labteto.dshmobile.local.model.LocalCanonicalContent.Text>()
                                        .joinToString("") { it.text }
                                }
                                outputLimitHasProgress = hasNewOutputLimitText(priorTexts, reply.content.orEmpty())
                            }
                            runHistory.append(reply.message)
                            workModelHistoryRuntime.updateContextMetrics(binding)
                            runTranscript.applyMessages(transcriptMessages, assistantEvent.sequence)
                            workModelHistoryRuntime.persist(binding)
                        }
                        is AgentEvent.ToolStarted -> {
                            val declared = runEventLog.append("tool/call", buildJsonObject {
                                put("source_message_id", uiSourceMessageId)
                                put("goal_id", binding.workState.snapshot().goal?.id ?: "")
                                put("step", event.step)
                                put("id", event.call.id)
                                put("name", event.call.name)
                                put("arguments", event.call.arguments)
                                put("execution_started", false)
                            })
                            declaredToolEvents[event.call.id] = declared.createdAt to declared.sequence
                            // Tool progress is projected from durable EventLog events, not a work-live card.
                        }
                        is AgentEvent.ToolFinished -> {
                            progressTracker.recordToolResult(event.call, event.output, event.isError)
                            val delivery = com.labteto.dshmobile.local.model.deliverLocalToolResult(
                                retain = {
                                    workToolResultRuntime.retain(
                                        binding = binding,
                                        callId = event.call.id,
                                        result = event.output,
                                        retention = event.retention,
                                        requireStored = event.richContent != null,
                                        preview = event.richContent?.modelPreview(),
                                    )
                                },
                                materialize = { workToolResultRuntime.importToolImages(event.richContent) },
                            )
                            agentRunCoordinator.ensureCurrentOwner(runContext)
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val boundedContent = delivery.content
                            val toolImages = delivery.images
                            val deliveryIsError = event.isError || delivery.failed
                            val deliveryErrorCode = if (delivery.failed) "TOOL_RESULT_DELIVERY_FAILED" else event.errorCode
                            val deliveryRetryable = !delivery.failed && event.retryable
                            val modelOutput = AgentToolResult(
                                content = boundedContent,
                                isError = deliveryIsError,
                                errorCode = deliveryErrorCode,
                                retryable = deliveryRetryable,
                                sideEffect = event.sideEffect,
                                recoveryHint = event.recoveryHint,
                            ).modelVisibleContent()
                            val declaredToolEvent = declaredToolEvents.remove(event.call.id)
                            val transcriptMessage = runTranscript.newMessage(
                                role = "tool",
                                content = durableToolResultContent(boundedContent, event.retention),
                                toolName = event.call.name,
                                blocks = toolImages.map { image -> com.labteto.dshmobile.local.session.LocalMessageBlock.Image(
                                    relativePath = image.relativePath, mediaType = image.mediaType, name = image.name,
                                    bytes = image.bytes, attachmentId = image.attachmentId, width = image.width, height = image.height,
                                    source = com.labteto.dshmobile.local.session.LocalMessageMediaSource.TOOL,
                                ) },
                                contentAlreadyBounded = true,
                                toolIsError = deliveryIsError,
                                toolErrorCode = deliveryErrorCode,
                                toolCallId = event.call.id,
                                toolExecutionId = event.executionId,
                                toolDeclaredAt = declaredToolEvent?.first,
                                toolDeclaredSequence = declaredToolEvent?.second,
                            )
                            val artifactVersion = if (!deliveryIsError) localWorkArtifactFileVersion(
                                java.io.File(sessionStorage.files.workspace.path), event.call.name, event.output,
                            ) else null
                            val toolEvent = runEventLog.append("tool/result", buildJsonObject {
                                declaredToolEvent?.second?.let { put("tool_declared_sequence", it) }
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
                                com.labteto.dshmobile.local.model.retainedToolOutputReference(boundedContent)?.let { put("tool_output_ref", it) }
                                put("tool_images", com.labteto.dshmobile.local.model.toolImageReferences(toolImages))
                                put("is_error", deliveryIsError)
                                artifactVersion?.let {
                                    put("artifact_path", it.path)
                                    put("artifact_sha256", it.sha256)
                                    put("artifact_bytes", it.bytes)
                                }
                                put("retention", event.retention.name.lowercase())
                                deliveryErrorCode?.let { put("error_code", it) }
                                put("retryable", deliveryRetryable)
                                put("side_effect", event.sideEffect.name.lowercase())
                                event.recoveryHint?.let { put("recovery_hint", it) }
                                put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
                            })
                            runHistory.append(localToolHistoryMessage(event.call.id, modelOutput, event.retention, toolImages))
                            completedToolCallIds += event.call.id
                            if (!deliveryIsError && event.call.name == "exit_plan_mode" &&
                                event.output == APPROVED_PLAN_TOOL_RESULT
                            ) {
                                approvedPlanPromptPending = true
                            }
                            // Update prompt only after ALL tool receipts in this assistant batch exist.
                            // A system message between tool_calls and tool results corrupts the history.
                            if (approvedPlanPromptPending &&
                                activeToolCalls.all { it.id in completedToolCallIds }
                            ) {
                                workModelHistoryRuntime.publishApprovedPlanPrompt(binding)
                                approvedPlanPromptPending = false
                            }
                            // Count only real tool receipts after their authoritative event is durable.
                            if (deliveryIsError) hadToolFailure = true else hadSuccessfulToolReceipt = true
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
                            val goalAssessment = recordWorkCompletionQuality(
                                event.answer, runState.value, runEventLog, File(workspacePath))
                            // Preserve approval/question/team blockers observed BEFORE teardown.
                            runEventLog.append("turn/end", buildJsonObject {
                                put("source_message_id", uiSourceMessageId)
                                // The model finished this turn; Work goal completion is independent.
                                put("reason", "completed")
                                put("steps", event.steps)
                                put("messages", runState.value.transcriptIndex.totalMessageCount)
                                goalAssessment?.toTurnEventFields()?.forEach { (key, value) ->
                                    put(key, value)
                                }
                            })
                            workModelHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/completed")
                            completedGoalDisposition = goalAssessment?.disposition
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
                        }
                        is AgentEvent.TurnFailed -> {
                            val completionReason = event.completionReason
                            if (completionReason != null) {
                                foregroundOutcome = LocalExecutionService.OUTCOME_FAILED
                                runEventLog.append("turn/end", buildJsonObject {
                                    put("source_message_id", uiSourceMessageId)
                                    put("reason", completionReason.name.lowercase())
                                    put("detail", event.reason)
                                })
                                workModelHistoryRuntime.checkpointAtTurnBoundary(binding, "turn/model-partial")
                                workModelHistoryRuntime.persist(binding)
                                val queued = event.completionReason == com.labteto.dshmobile.harness.agent.AgentCompletionReason.OUTPUT_LIMIT &&
                                    outputLimitHasProgress && !waitingForReplyFormSubmission &&
                                    queueAutomaticWorkContinuation(binding, runContext.runId,
                                        LocalModelException("MODEL_OUTPUT_LIMIT", "已保留部分正文，继续剩余内容。", false),
                                        uiSourceMessageId, outputLimitProgress = true)
                                if (!queued) binding.state.update { it.copy(error = "模型未完成：${event.reason}；已保留部分正文。") }
                            } else {
                                settlePendingTools("failed")
                                val detail = event.reason.take(2_000)
                                val continuationEligible =
                                    !waitingForReplyFormSubmission && shouldAutoContinueWorkFailure(
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
                ) {
                    withContext(agentRunCoordinator.executionContext(runContext)) { loop.run(input) }
                }
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
                val queued = if (modelError != null && !waitingForReplyFormSubmission) {
                    queueAutomaticWorkContinuation(binding, runContext.runId, modelError, uiSourceMessageId)
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
                try {
                    workModelHistoryRuntime.persist(binding)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    AppLog.failure("WorkAgentTurn", "persist_work_checkpoint", error,
                        sessionId = foregroundSessionId, runId = runContext.runId)
                    binding.state.update { current -> current.copy(error = "Work 回合已结束，快照保存失败：${error.message}") }
                } finally {
                    try {
                        try {
                            withContext(kotlinx.coroutines.NonCancellable) {
                                cpuProjection?.cancelAndJoin()
                                lease.release(foregroundOutcome)
                            }
                        }
                        finally { serviceReleased = true }
                    } finally {
                        workRunRegistry.finishTurn(binding, ownerJob, onIdle = {
                            if (shouldAutoContinueWorkGoalAfterForm(
                                    completedGoalDisposition, waitingForReplyFormSubmission)) {
                                driveLocalWorkGoalAfterTurn(
                                    binding = binding,
                                    originalGoal = goalAtTurnStart,
                                    sourceRunId = runContext.runId,
                                    sourceMessageId = uiSourceMessageId,
                                    originalSteeringRevision = steeringRevisionAtTurnStart,
                                    hadSuccessfulTool = hadSuccessfulToolReceipt,
                                    hadToolFailure = hadToolFailure,
                                    workspaceRoot = File(workspacePath),
                                    completeGoal = { expected ->
                                        LocalWorkProgressCoordinator(
                                            state = binding.workState,
                                            eventLog = runEventLog,
                                            workspaceRoot = File(workspacePath),
                                            persist = { sessionStorage.enqueueSnapshot(binding.persistenceSnapshot()) },
                                        ).completeVerifiedGoal(expected)
                                    },
                                )
                            }
                        }) { next, ownedBinding ->
                            scope.launch(start = CoroutineStart.LAZY) {
                                run(
                                    input = next.content,
                                    memoryInput = next.memoryInput,
                                    sourceMessageId = next.continuation?.sourceMessageId ?: next.id,
                                    binding = ownedBinding,
                                    continuation = next.continuation,
                                )
                            }
                        }?.start()
                    }
                }
            }
        }
    } finally {
        try {
            withContext(kotlinx.coroutines.NonCancellable) {
                cpuProjection?.cancelAndJoin()
                if (!serviceReleased) foregroundLease?.release(LocalExecutionService.OUTCOME_FAILED)
            }
        } finally {
            if (ownerJob != null) {
                workRunRegistry.releaseCompletedTurn(binding, ownerJob)
                // Derived human-readable archive refresh is independent of task success and
                // cannot mutate the completed run, replay tools, or change goal admission.
                onTurnSettled(binding, runContext.runId, selectedImprovement)
            }
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
