package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentEvent
import com.labteto.dshmobile.harness.agent.AgentEventSink
import com.labteto.dshmobile.harness.agent.AgentLoop
import com.labteto.dshmobile.harness.agent.AgentModel
import com.labteto.dshmobile.harness.agent.AgentModelReply
import com.labteto.dshmobile.harness.agent.AgentStepLimitExtender
import com.labteto.dshmobile.harness.agent.AgentStopReason
import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.agent.AgentToolExecutor
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.modelVisibleContent
import com.labteto.dshmobile.harness.jobs.JobContinuationPersistenceException
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalHistoryBudget
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.buildTokenUsageContext
import com.labteto.dshmobile.local.agent.LocalSubagentCompactionPolicy
import com.labteto.dshmobile.local.agent.LocalSubagentCapabilities
import com.labteto.dshmobile.local.agent.LocalSubagentHistoryMode
import com.labteto.dshmobile.local.agent.LocalSubagentLaunchSpec
import com.labteto.dshmobile.local.agent.LocalSubagentModelStepExecutor
import com.labteto.dshmobile.local.agent.validateLocalSubagentLaunchSpec
import com.labteto.dshmobile.local.agent.validateLocalSubagentToolAllowlist
import com.labteto.dshmobile.local.agent.filterLocalSubagentSchemas
import com.labteto.dshmobile.local.agent.LocalSubagentResult
import com.labteto.dshmobile.local.agent.LocalSubagentStatus
import com.labteto.dshmobile.local.agent.LocalSubagentToolCallPolicy
import com.labteto.dshmobile.local.agent.LocalStructuredSubagentOutputException
import com.labteto.dshmobile.local.agent.validateLocalStructuredSubagentOutput
import com.labteto.dshmobile.local.agent.buildLocalSubagentInitialHistory
import com.labteto.dshmobile.local.agent.inheritedHistoryBeforeToolCall
import com.labteto.dshmobile.local.agent.localAgentRunPolicy
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.model.LocalHistoryCompactor
import com.labteto.dshmobile.local.model.LocalImageInputMode
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelAdmissionPort
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.LocalModelRunContext
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.model.durableModelHistorySnapshot
import com.labteto.dshmobile.local.model.durableToolResultContent
import com.labteto.dshmobile.local.model.hasLocalImageRefs
import com.labteto.dshmobile.local.model.hasMaterializedImageUrls
import com.labteto.dshmobile.local.model.imageInputUnsupported
import com.labteto.dshmobile.local.model.localToolHistoryMessage
import com.labteto.dshmobile.local.model.toRunModelSurface
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.local.runtime.LOCAL_AGENT_RUN_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.runtime.LocalAgentProgressTracker
import com.labteto.dshmobile.local.runtime.LocalAgentRunCoordinator
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
import com.labteto.dshmobile.local.runtime.LocalAgentRunResourceBudget
import com.labteto.dshmobile.local.runtime.SUBAGENT_VIRTUAL_SCREEN_TOOLS
import com.labteto.dshmobile.local.runtime.adaptiveAgentStepLimit
import com.labteto.dshmobile.local.runtime.nextAdaptiveAgentStepLimit
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.tools.LocalModelToolStepSurface
import com.labteto.dshmobile.local.tools.LocalRunToolSurface
import com.labteto.dshmobile.observability.AppLog
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun runSubagentTurnBoundaryCleanup(
    primaryFailure: Exception?,
    cleanup: () -> Unit,
) {
    try {
        cleanup()
    } catch (cleanupError: Exception) {
        if (primaryFailure == null) throw cleanupError
        if (cleanupError !== primaryFailure) {
            primaryFailure.addSuppressed(cleanupError)
        }
    }
}

internal class LocalSubagentRunner(
    private val modelGateway: LocalModelGateway,
    private val state: StateFlow<LocalHarnessState>,
    private val jobs: LocalJobManager,
    private val historySnapshot: () -> List<JsonObject>,
    private val contextSnapshot: (String) -> String,
    private val eventLog: () -> LocalSessionEventLog,
    private val schemas: (Boolean, Boolean, MutableSet<String>) -> JsonArray,
    private val execute: suspend (LocalToolCall, Boolean, MutableSet<String>) -> AgentToolResult,
    private val spillToolOutput: (String, String) -> Boolean = { _, _ -> false },
    private val prepareMessages: suspend (List<JsonObject>, LocalImageInputMode, String, String) -> List<JsonObject>,
    private val resolveImageMode: (LocalImageInputMode, String, String) -> LocalImageInputMode,
    private val onUsage: (String, LocalModelReply, TokenUsageContext) -> Unit = { _, _, _ -> },
    private val onNativeImageAccepted: (String, String) -> Unit = { _, _ -> },
    private val onNativeImageRejected: (String, String) -> Unit = { _, _ -> },
    private val resourceScheduler: HarnessResourceScheduler,
    private val acquireVirtualScreen: suspend (String) -> String? = { null },
    private val releaseVirtualScreen: (String) -> Unit = { },
    private val historyBudget: ((LocalModelProfile) -> LocalHistoryBudget)? = null,
    private val historyCompactor: LocalHistoryCompactor = LocalHistoryCompactor(),
    private val runCoordinator: LocalAgentRunCoordinator? = null,
    private val runSessionId: () -> String = { state.value.sessionId },
    private val runKind: LocalAgentRunKind = LocalAgentRunKind.SUBAGENT,
    private val modelAdmission: LocalModelAdmissionPort? = null,
    private val compactionPolicy: LocalSubagentCompactionPolicy,
) {
    private val historyPolicy = com.labteto.dshmobile.local.agent.LocalSubagentHistoryPolicy(
        spillToolOutput = spillToolOutput,
        historyCompactor = historyCompactor,
        eventLog = eventLog,
    )

    private val modelStepExecutor = LocalSubagentModelStepExecutor(
        modelGateway = modelGateway,
        modelAttempts = { state.value.modelState.modelAttempts },
        resourceScheduler = resourceScheduler,
        eventLog = eventLog,
        historyCompactor = historyCompactor,
        admission = modelAdmission,
    )

    suspend fun run(
        spec: LocalSubagentLaunchSpec,
    ): String = runResult(spec).output

    internal fun prepareForkSeed(
        task: String,
        parentCallId: String,
        outputSchema: JsonObject? = null,
    ): List<JsonObject> =
        durableModelHistorySnapshot(
            buildLocalSubagentInitialHistory(
                baseHistory = inheritedHistoryBeforeToolCall(historySnapshot(), parentCallId),
                task = task,
                inheritParentHistory = true,
                allowMutation = false,
                context = contextSnapshot(task),
                outputSchema = outputSchema,
            ),
        )

    suspend fun runResult(
        spec: LocalSubagentLaunchSpec,
        recoveredHistory: List<JsonObject>? = null,
        recoveredClaimedMessageIds: Set<String> = emptySet(),
        recoveredStep: Int = 0,
        recoveredSoftStepLimit: Int? = null,
        resumeAfterCompletion: Boolean = false,
    ): LocalSubagentResult {
        val validated = validateLocalSubagentLaunchSpec(
            spec,
            structuredOutputSupported = true,
        )
        return resourceScheduler.withResource(
            HarnessResourceKind.AGENT,
            owner = "subagent:" + validated.task.take(80),
        ) {
            var virtualScreenId: String? = null
            try {
                if (validated.capabilities.virtualScreen) {
                    virtualScreenId = acquireVirtualScreen(validated.task.take(80))
                    require(virtualScreenId != null) {
                        "SUBAGENT_VIRTUAL_SCREEN_UNAVAILABLE：请求了独立虚拟屏，但当前无法分配"
                    }
                }
                runResultWithLease(
                    spec = validated,
                    virtualScreenId = virtualScreenId,
                    recoveredHistory = recoveredHistory,
                    recoveredClaimedMessageIds = recoveredClaimedMessageIds,
                    recoveredStep = recoveredStep,
                    recoveredSoftStepLimit = recoveredSoftStepLimit,
                    resumeAfterCompletion = resumeAfterCompletion,
                )
            } finally {
                virtualScreenId?.let(releaseVirtualScreen)
            }
        }
    }

    private suspend fun runResultWithLease(
        spec: LocalSubagentLaunchSpec,
        virtualScreenId: String?,
        recoveredHistory: List<JsonObject>?,
        recoveredClaimedMessageIds: Set<String>,
        recoveredStep: Int,
        recoveredSoftStepLimit: Int?,
        resumeAfterCompletion: Boolean,
    ): LocalSubagentResult {
        val task = spec.task
        val capabilities: LocalSubagentCapabilities = spec.capabilities
        val inheritHistory = capabilities.historyMode == LocalSubagentHistoryMode.INHERIT_PARENT
        val allowMutation = capabilities.allowMutation
        val backgroundJobId = spec.backgroundJobId
        val parentCallId = spec.parentCallId
        val modelOverride = spec.modelOverride
        val maxSteps = spec.maxSteps
        val subagentId = backgroundJobId
            ?.let(::localPersistentSubagentId)
            ?: "sa-" + UUID.randomUUID().toString().replace("-", "").take(12)
        val recoveringHistory = recoveredHistory != null
        val history = LocalModelHistoryBuffer().apply {
            reset(
                recoveredHistory
                    ?: if (inheritHistory) {
                        inheritedHistoryBeforeToolCall(historySnapshot(), parentCallId)
                    } else {
                        emptyList()
                    },
            )
        }
        history.reset(history.snapshot().map { JsonObject(it - LOCAL_WORK_EXECUTION_MODE_KEY) })
        val claimedMessageIds = linkedSetOf<String>().apply {
            addAll(recoveredClaimedMessageIds)
        }
        val progress = ArrayDeque<String>()
        val progressTracker = LocalAgentProgressTracker()
        val runProfile = try { modelGateway.profileForRun(modelOverride) } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return LocalSubagentResult(LocalSubagentStatus.FAILED,
                "[subagent][$subagentId] ${error.message}", (error as? LocalModelException)?.code ?: "NO_MODEL_CREDENTIAL")
        }
        val runSurface = runProfile.toRunModelSurface()
        val current = state.value
        val snapshot = current.copy(
            modelState = current.modelState.copy(model = runSurface.model, baseUrl = runSurface.baseUrl),
        )
        val routeModel = runSurface.model
        val runHistoryBudget = historyBudget?.invoke(runProfile)
        val runCachePolicy = runSurface.promptCachePolicy
        val runToolSurface = LocalRunToolSurface(runSurface)
        val stepLimit = adaptiveAgentStepLimit(
            configuredBase = maxSteps,
            task = task,
            contextChars = history.encodedChars + task.length,
            contextBudgetChars = runHistoryBudget?.maxHistoryChars ?: snapshot.kernel.contextBudgetChars,
            pressure = resourceScheduler.snapshot().pressure,
            kind = runKind,
        )
        val repliesByStep = mutableMapOf<Int, LocalModelReply>()
        val recoveredBaseStep = recoveredStep.coerceAtLeast(0)
        var activationStep = 0
        var modelStep = recoveredBaseStep
        var totalBudgetLimit = resolveSubagentTotalBudgetLimit(
            adaptiveStepLimit = stepLimit,
            recoveredStep = recoveredBaseStep,
            recoveredSoftStepLimit = recoveredSoftStepLimit,
            resumeAfterCompletion = resumeAfterCompletion,
            maxDynamicSteps = MAX_DYNAMIC_STEPS,
        )
        var pendingTerminalOutput: String? = null
        val modelToolStepSurface = LocalModelToolStepSurface()
        val toolCallPolicy = LocalSubagentToolCallPolicy(allowMutation, virtualScreenId, modelToolStepSurface)
        // Optional tool visibility belongs to this exact Agent run. A child discovering an MCP/LSP/
        // runtime capability must never make that capability appear in its parent or sibling run.
        val enabledOptionalTools = linkedSetOf<String>()
        val initialToolSchemas = schemas(
            allowMutation,
            virtualScreenId != null,
            enabledOptionalTools,
        )
        validateLocalSubagentToolAllowlist(initialToolSchemas, capabilities)

        eventLog().append("subagent/start", buildJsonObject {
            put("agent_id", subagentId)
            put("background_job_id", backgroundJobId ?: "")
            put("model", routeModel)
            put("profile_id", runProfile.id)
            put("auth_kind", runProfile.authKind.name)
            put("protocol", runProfile.protocol.name)
            put("base_url", runProfile.baseUrl)
            put("max_steps", stepLimit)
            put("task", task.take(2_000))
            put("allow_mutation", capabilities.allowMutation)
            put("continuable", capabilities.continuable)
            put("history_mode", capabilities.historyMode.name.lowercase())
            put("max_depth", capabilities.maxDepth)
            capabilities.toolAllowlist?.let { allowlist ->
                put("tool_allowlist", JsonArray(allowlist.sorted().map(::JsonPrimitive)))
            }
            virtualScreenId?.let { put("virtual_screen_id", it) }
        })
        if (!modelGateway.hasCredential(runProfile)) {
            val output = "[subagent][$subagentId][NO_MODEL_CREDENTIAL] 子代理无法读取当前模型凭据"
            eventLog().append("subagent/end", buildJsonObject {
                put("agent_id", subagentId)
                put("status", "failed")
                put("code", "NO_MODEL_CREDENTIAL")
            })
            return LocalSubagentResult(LocalSubagentStatus.FAILED, output, "NO_MODEL_CREDENTIAL")
        }
        val parentRunId = parentCallId?.let { callId ->
            eventLog().latestMatching(setOf(LOCAL_AGENT_RUN_CHECKPOINT_EVENT)) { data ->
                data["call_id"]?.jsonPrimitive?.contentOrNull == callId
            }?.data?.get("run_id")?.jsonPrimitive?.contentOrNull
        }
        val runContext = runCoordinator?.start(
            sessionId = runSessionId(),
            usageMode = LocalUsageMode.WORK,
            model = routeModel,
            baseUrl = snapshot.modelState.baseUrl,
            routeProfile = runProfile,
            planMode = snapshot.work.planMode,
            policy = localAgentRunPolicy(LocalUsageMode.WORK),
            safeAutoApprovalEnabled = snapshot.safeAutoApprovalEnabled,
            maxSteps = stepLimit,
            input = task,
            memoryInput = task,
            kind = runKind,
            allowMutation = allowMutation,
            resourceBudget = LocalAgentRunResourceBudget(
                maxModelRequests = snapshot.kernel.resources.maxModelRequests,
                maxAgents = snapshot.kernel.resources.maxAgents,
                maxTerminals = snapshot.kernel.resources.maxTerminals,
                maxVirtualDisplays = snapshot.kernel.resources.maxVirtualDisplays,
                maxLanguageServers = snapshot.kernel.resources.maxLanguageServers,
            ),
            toolNames = filterLocalSubagentSchemas(
                initialToolSchemas,
                capabilities,
            ).mapNotNull { element ->
                val function = (element as? JsonObject)?.get("function") as? JsonObject
                (function?.get("name") as? JsonPrimitive)?.content
            },
            contextChars = history.encodedChars + task.length,
            parentRunId = parentRunId,
            agentId = subagentId,
        )

        val resultIds = mutableMapOf<Int, String>()

        fun persistContinuationCheckpoint(
            step: Int,
            terminalOutput: String? = null,
        ) {
            val jobId = backgroundJobId ?: return
            val previousResultId = resultIds[step]
            val resultId = terminalOutput?.takeIf(String::isNotBlank)?.let {
                previousResultId ?: UUID.randomUUID().toString()
            }
            try {
                eventLog().append(
                    LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
                    encodeLocalSubagentHistoryCheckpoint(
                        backgroundJobId = jobId,
                        agentId = subagentId,
                        step = step,
                        history = durableModelHistorySnapshot(history.snapshot()),
                        claimedMessageIds = claimedMessageIds,
                        softStepLimit = totalBudgetLimit,
                        terminalOutput = terminalOutput,
                        resultId = resultId,
                        resultFirst = previousResultId == null,
                    ),
                )
                if (resultId != null) resultIds[step] = resultId
            } catch (error: Exception) {
                throw JobContinuationPersistenceException(
                    "持久子代理历史检查点写入失败",
                    error,
                )
            }
        }

        var primaryFailure: Exception? = null
        try {
            if (!recoveringHistory) {
                history.reset(
                    buildLocalSubagentInitialHistory(
                        baseHistory = history.snapshot(),
                        task = task,
                        inheritParentHistory = inheritHistory,
                        allowMutation = allowMutation,
                        context = contextSnapshot(task),
                        outputSchema = capabilities.outputSchema,
                    ),
                )
            }
            if (recoveringHistory) {
                history.reset(
                    history.snapshot().filterNot { message ->
                        message["role"]?.jsonPrimitive?.contentOrNull == "system" &&
                            message["content"]?.jsonPrimitive?.contentOrNull
                                ?.startsWith("【独立虚拟屏】id=") == true
                    },
                )
            }
            virtualScreenId?.let { id ->
                history.append(buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        "【独立虚拟屏】id=$id；界面操作仅用 android_vscreen_* 并传入该 id，禁止操作主屏。",
                    )
                })
            }
            backgroundJobId?.let { jobId ->
                val staleClaimedIds = jobs.peekMessages(jobId)
                    .mapTo(linkedSetOf()) { it.id }
                    .intersect(claimedMessageIds)
                if (staleClaimedIds.isNotEmpty()) {
                    jobs.acknowledgeMessages(jobId, staleClaimedIds, runSessionId())
                }
            }
            persistContinuationCheckpoint(step = modelStep)
            if (totalBudgetLimit <= recoveredBaseStep) {
                return LocalSubagentResult(
                    LocalSubagentStatus.STEP_LIMIT,
                    "[subagent][$subagentId][STEP_LIMIT] 已恢复到第 $recoveredBaseStep 步，当前执行预算已用尽；没有重跑已完成步骤。",
                    "STEP_LIMIT",
                )
            }
            val remainingSteps = totalBudgetLimit - recoveredBaseStep
            val loop = AgentLoop(
                model = AgentModel {
                    currentCoroutineContext().ensureActive()
                    backgroundJobId?.let { jobId ->
                        val queuedMessages = jobs.peekMessages(jobId)
                            .filterNot { message -> message.id in claimedMessageIds }
                        if (queuedMessages.isNotEmpty()) {
                            eventLog().append(
                                LOCAL_SUBAGENT_INBOX_CLAIM_EVENT,
                                encodeLocalSubagentInboxClaimEvent(
                                    agentId = subagentId,
                                    backgroundJobId = jobId,
                                    messages = queuedMessages,
                                ),
                            )
                            queuedMessages.forEach { message ->
                                history.append(buildJsonObject {
                                    put("role", "user")
                                    put("content", message.content)
                                })
                                claimedMessageIds += message.id
                            }
                            persistContinuationCheckpoint(step = modelStep)
                            jobs.acknowledgeMessages(
                                jobId,
                                queuedMessages.mapTo(linkedSetOf()) { it.id },
                                runSessionId(),
                            )
                        }
                    }
                    compactionPolicy.beforeModelStep(
                        historyPolicy,
                        history,
                        subagentId,
                        modelStep,
                        runHistoryBudget,
                        runCachePolicy,
                    )
                    activationStep += 1
                    modelStep = recoveredBaseStep + activationStep
                    val durableHistory = history.snapshot()
                    val selectedMode = resolveImageMode(snapshot.modelState.imageInputMode, snapshot.modelState.baseUrl, routeModel)
                    if (hasLocalImageRefs(durableHistory) &&
                        resolveImageMode(LocalImageInputMode.AUTO, snapshot.modelState.baseUrl, routeModel) == LocalImageInputMode.TOOL) {
                        throw IllegalStateException("当前模型不支持图片理解，请切换支持图片的模型后重试。")
                    }
                    val preparedHistory = prepareMessages(
                        durableHistory,
                        selectedMode,
                        snapshot.modelState.baseUrl,
                        routeModel,
                    )
                    val nativeImagesSent = hasMaterializedImageUrls(preparedHistory)
                    val reply = try {
                        modelStepExecutor.complete(
                            surface = runSurface,
                            history = preparedHistory,
                            tools = modelToolStepSurface.capture(
                                runToolSurface.next(
                                    filterLocalSubagentSchemas(
                                        schemas(
                                            allowMutation,
                                            virtualScreenId != null,
                                            enabledOptionalTools,
                                        ),
                                        capabilities,
                                    ),
                                ),
                            ),
                            subagentId = subagentId,
                            step = modelStep,
                            durableHistory = history,
                        ).also {
                            if (nativeImagesSent) {
                                onNativeImageAccepted(snapshot.modelState.baseUrl, routeModel)
                            }
                        }
                    } catch (error: Throwable) {
                        val nativeImageRejected =
                            nativeImagesSent &&
                                imageInputUnsupported(error)
                        if (nativeImageRejected) {
                            onNativeImageRejected(snapshot.modelState.baseUrl, routeModel)
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
                    currentCoroutineContext().ensureActive()
                    val usageAction = if (runKind == LocalAgentRunKind.AUTOMATION) {
                        TokenUsageAction.AUTOMATION
                    } else {
                        TokenUsageAction.WORK_SUBAGENT
                    }
                    val usageContext = buildTokenUsageContext(
                        snapshot = snapshot,
                        action = usageAction,
                        turnId = runContext?.runId ?: subagentId,
                        runId = runContext?.runId ?: subagentId,
                        parentRunId = parentRunId,
                        runKind = runKind,
                        agentId = subagentId,
                        taskLabel = task,
                        step = modelStep,
                    ).copy(sessionId = runSessionId())
                    onUsage(routeModel, reply, usageContext)
                    repliesByStep[activationStep] = reply
                    AgentModelReply(
                        content = reply.content.orEmpty(),
                        toolCalls = reply.toolCalls.map { call ->
                            AgentToolCall(
                                id = call.id,
                                name = call.name,
                                arguments = call.arguments,
                                rawArguments = call.rawArguments,
                            )
                        },
                    )
                },
                tools = AgentToolExecutor { call ->
                    currentCoroutineContext().ensureActive()
                    toolCallPolicy.rejection(call) ?: withContext(LocalModelRunContext(runProfile)) {
                        currentCoroutineContext().ensureActive()
                        execute(call.toLocalToolCall(), allowMutation, enabledOptionalTools)
                            .also { currentCoroutineContext().ensureActive() }
                    }
                },
                eventSink = AgentEventSink { event ->
                    when (event) {
                        is AgentEvent.AssistantObserved -> {
                            val absoluteStep = recoveredBaseStep + event.step
                            val reply = repliesByStep.remove(event.step)
                                ?: error("缺少子代理第 $absoluteStep 步模型响应")
                            progressTracker.recordAssistant(reply.content.orEmpty(), reply.toolCalls.size)
                            history.append(reply.message)
                            pendingTerminalOutput = if (reply.toolCalls.isEmpty()) {
                                reply.content.orEmpty().ifBlank { "子代理已结束，但没有返回文字。" }
                            } else {
                                null
                            }
                            if (capabilities.outputSchema == null) {
                                pendingTerminalOutput?.let { terminal ->
                                    persistContinuationCheckpoint(
                                        step = absoluteStep,
                                        terminalOutput = terminal,
                                    )
                                }
                            }
                            reply.content?.takeIf(String::isNotBlank)?.let { content ->
                                historyPolicy.rememberProgress(
                                    progress,
                                    "第 $absoluteStep 步回复：${content.take(1_500)}",
                                )
                            }
                        }
                        is AgentEvent.ToolStarted -> {
                            val absoluteStep = recoveredBaseStep + event.step
                            eventLog().append("subagent/tool-call", buildJsonObject {
                                put("agent_id", subagentId)
                                put("step", absoluteStep)
                                put("id", event.call.id)
                                put("name", event.call.name)
                                put("arguments", event.call.arguments)
                                put("execution_started", false)
                            })
                        }
                        is AgentEvent.ToolFinished -> {
                            val absoluteStep = recoveredBaseStep + event.step
                            progressTracker.recordToolResult(event.call, event.output, event.isError)
                            val boundedContent = historyPolicy.retainToolResult(
                                event.call.id,
                                event.output,
                                event.retention,
                            )
                            val modelOutput = AgentToolResult(
                                content = boundedContent,
                                isError = event.isError,
                                errorCode = event.errorCode,
                                retryable = event.retryable,
                                sideEffect = event.sideEffect,
                                recoveryHint = event.recoveryHint,
                            ).modelVisibleContent()
                            historyPolicy.rememberProgress(
                                progress,
                                "第 $absoluteStep 步 · ${event.call.name}：" +
                                    truncateWithoutSplittingSurrogatePair(
                                        durableToolResultContent(event.output, event.retention), 1_500,
                                    ),
                            )
                            eventLog().append("subagent/tool-result", buildJsonObject {
                                put("agent_id", subagentId)
                                put("step", absoluteStep)
                                put("id", event.call.id)
                                put("name", event.call.name)
                                put(
                                    "content",
                                    truncateWithoutSplittingSurrogatePair(
                                        durableToolResultContent(event.output, event.retention), SUBAGENT_EVENT_CHARS,
                                    ),
                                )
                                put("model_content", durableToolResultContent(modelOutput, event.retention))
                                put("is_error", event.isError)
                                event.errorCode?.let { put("error_code", it) }
                                put("retryable", event.retryable)
                                put("side_effect", event.sideEffect.name.lowercase())
                                event.recoveryHint?.let { put("recovery_hint", it) }
                            })
                            history.append(localToolHistoryMessage(event.call.id, modelOutput, event.retention))
                        }
                        is AgentEvent.StepFinished -> {
                            if (pendingTerminalOutput == null) {
                                persistContinuationCheckpoint(
                                    step = recoveredBaseStep + event.step,
                                )
                            }
                        }
                        is AgentEvent.TurnCompleted -> {
                            if (capabilities.outputSchema == null) {
                                eventLog().append("subagent/end", buildJsonObject {
                                    put("agent_id", subagentId)
                                    put("status", "completed")
                                    put("steps", recoveredBaseStep + event.steps)
                                })
                                persistContinuationCheckpoint(
                                    step = recoveredBaseStep + event.steps,
                                    terminalOutput = event.answer.ifBlank { "子代理已结束，但没有返回文字。" },
                                )
                            }
                        }
                        is AgentEvent.TurnStepLimit -> {
                            eventLog().append("subagent/end", buildJsonObject {
                                put("agent_id", subagentId)
                                put("status", "step_limit")
                                put("steps", recoveredBaseStep + event.steps)
                            })
                        }
                        is AgentEvent.TurnCancelled -> {
                            eventLog().append("subagent/end", buildJsonObject {
                                put("agent_id", subagentId)
                                put("status", "cancelled")
                            })
                        }
                        is AgentEvent.TurnFailed -> {
                            eventLog().append("subagent/end", buildJsonObject {
                                put("agent_id", subagentId)
                                put("status", "failed")
                                put("detail", event.reason.take(2_000))
                            })
                        }
                        else -> Unit
                    }
                    runContext?.let { context ->
                        runCoordinator?.recordEvent(
                            context,
                            event.withStepOffset(recoveredBaseStep),
                        )
                    }
                },
                maxSteps = remainingSteps,
                stepLimitExtender = AgentStepLimitExtender { currentLimit, stepsUsed ->
                    val liveBudget = historyBudget?.invoke(runProfile)
                    val currentTotalLimit = recoveredBaseStep + currentLimit
                    totalBudgetLimit = maxOf(totalBudgetLimit, currentTotalLimit)
                    if (currentTotalLimit >= MAX_DYNAMIC_STEPS) return@AgentStepLimitExtender null
                    if (!progressTracker.claimExtensionProgress()) {
                        eventLog().append("subagent/budget-stopped", buildJsonObject {
                            put("agent_id", subagentId)
                            put("steps_used", recoveredBaseStep + stepsUsed)
                            put("reason", "no-new-evidence")
                        })
                        return@AgentStepLimitExtender null
                    }
                    val next = nextAdaptiveAgentStepLimit(
                        currentLimit = currentTotalLimit,
                        configuredBase = maxSteps,
                        task = task,
                        contextChars = history.encodedChars,
                        contextBudgetChars = liveBudget?.maxHistoryChars ?: state.value.kernel.contextBudgetChars,
                        pressure = resourceScheduler.snapshot().pressure,
                        kind = runKind,
                    )
                    val boundedNextTotal = next?.coerceAtMost(MAX_DYNAMIC_STEPS)
                    if (boundedNextTotal != null && boundedNextTotal > currentTotalLimit) {
                        totalBudgetLimit = boundedNextTotal
                        persistContinuationCheckpoint(
                            step = recoveredBaseStep + stepsUsed,
                        )
                        eventLog().append("subagent/budget-extended", buildJsonObject {
                            put("agent_id", subagentId)
                            put("steps_used", recoveredBaseStep + stepsUsed)
                            put("previous_limit", currentTotalLimit)
                            put("next_limit", boundedNextTotal)
                            put("context_chars", history.encodedChars)
                            put("resource_pressure", resourceScheduler.snapshot().pressure.name.lowercase())
                        })
                    }
                    boundedNextTotal
                        ?.minus(recoveredBaseStep)
                        ?.takeIf { it > currentLimit }
                },
                idFactory = { runContext?.runId ?: UUID.randomUUID().toString() },
            )

            val result = loop.run(task)
            if (result.stopReason == com.labteto.dshmobile.harness.agent.AgentStopReason.COMPLETED) {
                val answer = result.answer.ifBlank { "子代理已结束，但没有返回文字。" }
                val outputSchema = capabilities.outputSchema
                if (outputSchema == null) {
                    return LocalSubagentResult(
                        status = LocalSubagentStatus.COMPLETED,
                        output = answer,
                    )
                }

                val structured = validateLocalStructuredSubagentOutput(answer, outputSchema)
                val failure = structured.exceptionOrNull() as? LocalStructuredSubagentOutputException
                if (failure != null) {
                    val absoluteSteps = recoveredBaseStep + result.steps
                    eventLog().append("subagent/end", buildJsonObject {
                        put("agent_id", subagentId)
                        put("status", "failed")
                        put("steps", absoluteSteps)
                        put("code", failure.failure.code)
                        put("detail", failure.failure.detail.take(2_000))
                    })
                    persistContinuationCheckpoint(step = absoluteSteps)
                    return LocalSubagentResult(
                        status = LocalSubagentStatus.FAILED,
                        output =
                            "[subagent][$subagentId][${failure.failure.code}] " +
                                "结构化结果校验失败：${failure.failure.detail}",
                        errorCode = failure.failure.code,
                    )
                }
                val verified = structured.getOrThrow()
                val absoluteSteps = recoveredBaseStep + result.steps
                eventLog().append("subagent/structured-result", buildJsonObject {
                    put("agent_id", subagentId)
                    put("step", absoluteSteps)
                    put("schema_digest", verified.schemaDigest)
                    put("result_digest", verified.resultDigest)
                })
                eventLog().append("subagent/end", buildJsonObject {
                    put("agent_id", subagentId)
                    put("status", "completed")
                    put("steps", absoluteSteps)
                    put("structured", true)
                })
                persistContinuationCheckpoint(
                    step = absoluteSteps,
                    terminalOutput = verified.canonicalJson,
                )
                return LocalSubagentResult(
                    status = LocalSubagentStatus.COMPLETED,
                    output = verified.canonicalJson,
                    structuredOutput = verified.value,
                )
            }

            val partial = progress.joinToString("\n")
            val output = buildString {
                append(
                    "[subagent][$subagentId][STEP_LIMIT] 动态执行预算已无法继续扩展，任务在第 " +
                        "${recoveredBaseStep + result.steps} 步暂停。",
                )
                if (partial.isNotBlank()) {
                    append("\n已完成的最近进度：\n")
                    append(partial)
                }
                append("\n已有进度已保留；正常情况下预算会自动续算直到任务完成。")
            }
            return LocalSubagentResult(LocalSubagentStatus.STEP_LIMIT, output, "STEP_LIMIT")
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                primaryFailure = cancelled
                throw cancelled
            }
            val partial = progress.joinToString("\n")
            val output = buildString {
                append("[subagent][$subagentId][TASK_CANCELLED] 子代理自身被取消；同批其他子代理不会被级联取消。")
                cancelled.message?.takeIf(String::isNotBlank)?.let { append("\n原因：$it") }
                if (partial.isNotBlank()) append("\n已完成的最近进度：\n$partial")
            }
            return LocalSubagentResult(LocalSubagentStatus.CANCELLED, output, "TASK_CANCELLED")
        } catch (error: JobContinuationPersistenceException) {
            // The outer persistent Job owner is the authority for this failure. Converting it into
            // a normal subagent failure would permanently mark a safely resumable activation as failed.
            primaryFailure = error
            throw error
        } catch (error: LocalModelException) {
            AppLog.warn(
                "LocalSubagentRunner",
                "子智能体模型失败 agent=$subagentId model=$routeModel code=${error.code} detail=${error.message.orEmpty().take(800)}",
            )
            val partial = progress.joinToString("\n")
            val output = buildString {
                append("[subagent][$subagentId][${error.code}] 模型阶段失败：${error.message}")
                if (partial.isNotBlank()) append("\n已完成的最近进度：\n$partial")
                append("\n建议：模型超时可重试；网页/工具超时请查看对应工具错误码。")
            }
            return LocalSubagentResult(LocalSubagentStatus.FAILED, output, error.code, error.retryable)
        } catch (error: Exception) {
            val partial = progress.joinToString("\n")
            val output = buildString {
                append("[subagent][$subagentId][SUBAGENT_ERROR] ${error.message ?: error::class.java.simpleName}")
                if (partial.isNotBlank()) append("\n已完成的最近进度：\n$partial")
            }
            return LocalSubagentResult(LocalSubagentStatus.FAILED, output, "SUBAGENT_ERROR")
        } finally {
            runSubagentTurnBoundaryCleanup(primaryFailure) {
                compactionPolicy.atTurnBoundary(
                    historyPolicy,
                    history,
                    subagentId,
                    runHistoryBudget,
                    runCachePolicy,
                )
            }
        }
    }

    private fun AgentEvent.withStepOffset(offset: Int): AgentEvent {
        if (offset == 0) return this
        return when (this) {
            is AgentEvent.StepStarted -> copy(step = offset + step)
            is AgentEvent.AssistantObserved -> copy(step = offset + step)
            is AgentEvent.ToolStarted -> copy(step = offset + step)
            is AgentEvent.ToolFinished -> copy(step = offset + step)
            is AgentEvent.StepFinished -> copy(step = offset + step)
            is AgentEvent.TurnCompleted -> copy(steps = offset + steps)
            is AgentEvent.TurnStepLimit -> copy(steps = offset + steps)
            is AgentEvent.TurnStarted,
            is AgentEvent.TurnFailed,
            is AgentEvent.TurnCancelled -> this
        }
    }

    private fun AgentToolCall.toLocalToolCall() = LocalToolCall(id, name, arguments, rawArguments)
    private companion object {
        const val MAX_DYNAMIC_STEPS = 512
        const val SUBAGENT_EVENT_CHARS = 65_536
        val SUBAGENT_VIRTUAL_SCREEN_TOOLS = setOf(
            "android_vscreen_status",
            "android_vscreen_launch",
            "android_vscreen_tap",
            "android_vscreen_swipe",
            "android_vscreen_screenshot",
            "vision_analyze_vscreen",
        )
    }
}
