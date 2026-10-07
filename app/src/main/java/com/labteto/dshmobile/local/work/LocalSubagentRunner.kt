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
import com.labteto.dshmobile.local.agent.LocalSubagentModelStepExecutor
import com.labteto.dshmobile.local.agent.LocalSubagentResult
import com.labteto.dshmobile.local.agent.LocalSubagentStatus
import com.labteto.dshmobile.local.agent.LocalSubagentToolCallPolicy
import com.labteto.dshmobile.local.agent.boundedSubagentContext
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
import com.labteto.dshmobile.local.model.stableJsonSha256
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
        task: String,
        inheritHistory: Boolean,
        allowMutation: Boolean,
        backgroundJobId: String? = null,
        parentCallId: String? = null,
        modelOverride: String? = null,
        maxSteps: Int = state.value.subagentMaxSteps,
        virtualScreen: Boolean = false,
        recoveredHistory: List<JsonObject>? = null,
        recoveredClaimedMessageIds: Set<String> = emptySet(),
        recoveredStep: Int = 0,
    ): String = runResult(
        task = task,
        inheritHistory = inheritHistory,
        allowMutation = allowMutation,
        backgroundJobId = backgroundJobId,
        parentCallId = parentCallId,
        modelOverride = modelOverride,
        maxSteps = maxSteps,
        virtualScreen = virtualScreen,
        recoveredHistory = recoveredHistory,
        recoveredClaimedMessageIds = recoveredClaimedMessageIds,
        recoveredStep = recoveredStep,
    ).output

    suspend fun runResult(
        task: String,
        inheritHistory: Boolean,
        allowMutation: Boolean,
        backgroundJobId: String? = null,
        parentCallId: String? = null,
        modelOverride: String? = null,
        maxSteps: Int = state.value.subagentMaxSteps,
        virtualScreen: Boolean = false,
        recoveredHistory: List<JsonObject>? = null,
        recoveredClaimedMessageIds: Set<String> = emptySet(),
        recoveredStep: Int = 0,
    ): LocalSubagentResult = resourceScheduler.withResource(
        HarnessResourceKind.AGENT,
        owner = "subagent:" + task.take(80),
    ) {
        var virtualScreenId: String? = null
        try {
            if (virtualScreen) {
                virtualScreenId = acquireVirtualScreen(task.take(80))
            }
            runResultWithLease(
                task = task,
                inheritHistory = inheritHistory,
                allowMutation = allowMutation,
                backgroundJobId = backgroundJobId,
                parentCallId = parentCallId,
                modelOverride = modelOverride,
                maxSteps = maxSteps,
                virtualScreenId = virtualScreenId,
                recoveredHistory = recoveredHistory,
                recoveredClaimedMessageIds = recoveredClaimedMessageIds,
                recoveredStep = recoveredStep,
            )
        } finally {
            virtualScreenId?.let(releaseVirtualScreen)
        }
    }

    private suspend fun runResultWithLease(
        task: String,
        inheritHistory: Boolean,
        allowMutation: Boolean,
        backgroundJobId: String?,
        parentCallId: String?,
        modelOverride: String?,
        maxSteps: Int,
        virtualScreenId: String?,
        recoveredHistory: List<JsonObject>?,
        recoveredClaimedMessageIds: Set<String>,
        recoveredStep: Int,
    ): LocalSubagentResult {
        require(backgroundJobId == null || !allowMutation) {
            "持久子代理只支持只读执行，避免冷恢复重放未知副作用"
        }
        val subagentId = backgroundJobId
            ?.let(::persistentSubagentId)
            ?: "sa-" + UUID.randomUUID().toString().replace("-", "").take(12)
        val recoveringHistory = recoveredHistory != null
        val activationInput = if (recoveringHistory) {
            "继续处理持久子代理已排队的新消息，并基于已有历史完成本轮任务。"
        } else {
            task
        }
        val history = LocalModelHistoryBuffer().apply {
            reset(
                recoveredHistory
                    ?: if (inheritHistory) inheritedHistoryBeforeToolCall(historySnapshot(), parentCallId)
                    else emptyList(),
            )
        }
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
        var modelStep = recoveredStep.coerceAtLeast(0)
        val modelToolStepSurface = LocalModelToolStepSurface()
        val toolCallPolicy = LocalSubagentToolCallPolicy(allowMutation, virtualScreenId, modelToolStepSurface)
        // Optional tool visibility belongs to this exact Agent run. A child discovering an MCP/LSP/
        // runtime capability must never make that capability appear in its parent or sibling run.
        val enabledOptionalTools = linkedSetOf<String>()

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
            input = activationInput,
            memoryInput = if (recoveringHistory) activationInput else task,
            kind = runKind,
            allowMutation = allowMutation,
            resourceBudget = LocalAgentRunResourceBudget(
                maxModelRequests = snapshot.kernel.resources.maxModelRequests,
                maxAgents = snapshot.kernel.resources.maxAgents,
                maxTerminals = snapshot.kernel.resources.maxTerminals,
                maxVirtualDisplays = snapshot.kernel.resources.maxVirtualDisplays,
                maxLanguageServers = snapshot.kernel.resources.maxLanguageServers,
            ),
            toolNames = schemas(
                allowMutation,
                virtualScreenId != null,
                enabledOptionalTools,
            ).mapNotNull { element ->
                val function = (element as? JsonObject)?.get("function") as? JsonObject
                (function?.get("name") as? JsonPrimitive)?.content
            },
            contextChars = history.encodedChars + task.length,
            parentRunId = parentRunId,
            agentId = subagentId,
        )

        fun persistContinuationCheckpoint(step: Int) {
            val jobId = backgroundJobId ?: return
            val checkpoint = encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = jobId,
                agentId = subagentId,
                step = step,
                history = durableModelHistorySnapshot(history.snapshot()),
                claimedMessageIds = claimedMessageIds,
            )
            jobs.updateContinuationState(
                id = jobId,
                state = checkpoint.toString(),
                ownerSessionId = runSessionId(),
            )
            runCatching {
                eventLog().append(
                    LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
                    buildJsonObject {
                        put("version", 1)
                        put("background_job_id", jobId)
                        put("agent_id", subagentId)
                        put("step", step.coerceAtLeast(0))
                        put("message_count", history.snapshot().size)
                        put("claimed_message_count", claimedMessageIds.size)
                        put("state_digest", stableJsonSha256(checkpoint))
                        put("storage", "job_snapshot")
                    },
                )
            }.onFailure { error ->
                AppLog.warn(
                    "LocalSubagentRunner",
                    "子代理续跑检查点事实写入失败 agent=$subagentId step=$step",
                    error,
                )
            }
        }

        fun claimQueuedMessages(): Int {
            val jobId = backgroundJobId ?: return 0
            val queuedMessages = jobs.peekMessages(jobId)
                .filterNot { message -> message.id in claimedMessageIds }
            if (queuedMessages.isEmpty()) return 0
            eventLog().append("subagent/inbox-claimed", buildJsonObject {
                put("version", 1)
                put("agent_id", subagentId)
                put("background_job_id", jobId)
                put("messages", JsonArray(queuedMessages.map { message ->
                    buildJsonObject {
                        put("id", message.id)
                        put("content", message.content)
                    }
                }))
            })
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
            return queuedMessages.size
        }

        try {
            if (!recoveringHistory) {
                if (!inheritHistory) history.append(buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        if (allowMutation) {
                            "你是执行子代理。完成指定子任务，按权限使用可用能力，并核实结果后返回。"
                        } else {
                            "你是只读子代理。完成指定子任务；仅允许读取、搜索和分析，不修改状态。"
                        },
                    )
                })
                boundedSubagentContext(contextSnapshot(task))?.let { inherited ->
                    val insertion = buildJsonObject {
                        put("role", "system")
                        put(
                            "content",
                            "【父任务约束】\n$inherited\n遵守以上约束；本子任务的明确更新优先。",
                        )
                    }
                    val index = if (
                        history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system"
                    ) 1 else 0
                    history.insert(index, insertion)
                }
                history.append(buildJsonObject { put("role", "user"); put("content", task) })
            }
            if (recoveringHistory) {
                history.removeAll { message ->
                    message["role"]?.jsonPrimitive?.contentOrNull == "system" &&
                        message["content"]?.jsonPrimitive?.contentOrNull
                            ?.startsWith("【独立虚拟屏】id=") == true
                }
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
            if (recoveringHistory && modelStep >= stepLimit) {
                return LocalSubagentResult(
                    status = LocalSubagentStatus.STEP_LIMIT,
                    output = "[subagent][$subagentId][STEP_LIMIT] 已恢复到既定步数上限，未重放额外模型步骤。",
                    errorCode = "STEP_LIMIT",
                )
            }
            val activationStepOffset = modelStep
            val remainingSteps = (stepLimit - activationStepOffset).coerceAtLeast(1)
            val loop = AgentLoop(
                model = AgentModel {
                    currentCoroutineContext().ensureActive()
                    claimQueuedMessages()
                    compactionPolicy.beforeModelStep(historyPolicy, history, subagentId, modelStep, runHistoryBudget, runCachePolicy)
                    modelStep += 1
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
                            tools = modelToolStepSurface.capture(runToolSurface.next(schemas(allowMutation, virtualScreenId != null, enabledOptionalTools))),
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
                    repliesByStep[modelStep] = reply
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
                    val durableEvent = event.withStepOffset(activationStepOffset)
                    when (durableEvent) {
                        is AgentEvent.AssistantObserved -> {
                            val reply = repliesByStep.remove(durableEvent.step)
                                ?: error("缺少子代理第 ${durableEvent.step} 步模型响应")
                            progressTracker.recordAssistant(reply.content.orEmpty(), reply.toolCalls.size)
                            history.append(reply.message)
                            reply.content?.takeIf(String::isNotBlank)?.let { content ->
                                historyPolicy.rememberProgress(
                                    progress,
                                    "第 ${durableEvent.step} 步回复：${content.take(1_500)}",
                                )
                            }
                        }
                        is AgentEvent.ToolStarted -> {
                            eventLog().append("subagent/tool-call", buildJsonObject {
                                put("agent_id", subagentId)
                                put("step", durableEvent.step)
                                put("id", durableEvent.call.id)
                                put("name", durableEvent.call.name)
                                put("arguments", durableEvent.call.arguments)
                                put("execution_started", false)
                            })
                        }
                        is AgentEvent.ToolFinished -> {
                            progressTracker.recordToolResult(durableEvent.call, durableEvent.output, durableEvent.isError)
                            val boundedContent = historyPolicy.retainToolResult(
                                durableEvent.call.id,
                                durableEvent.output,
                                durableEvent.retention,
                            )
                            val modelOutput = AgentToolResult(
                                content = boundedContent,
                                isError = durableEvent.isError,
                                errorCode = durableEvent.errorCode,
                                retryable = durableEvent.retryable,
                                sideEffect = durableEvent.sideEffect,
                                recoveryHint = durableEvent.recoveryHint,
                            ).modelVisibleContent()
                            historyPolicy.rememberProgress(
                                progress,
                                "第 ${durableEvent.step} 步 · ${durableEvent.call.name}：" +
                                    truncateWithoutSplittingSurrogatePair(
                                        durableToolResultContent(durableEvent.output, durableEvent.retention), 1_500,
                                    ),
                            )
                            eventLog().append("subagent/tool-result", buildJsonObject {
                                put("agent_id", subagentId)
                                put("step", durableEvent.step)
                                put("id", durableEvent.call.id)
                                put("name", durableEvent.call.name)
                                put(
                                    "content",
                                    truncateWithoutSplittingSurrogatePair(
                                        durableToolResultContent(durableEvent.output, durableEvent.retention), SUBAGENT_EVENT_CHARS,
                                    ),
                                )
                                put("model_content", durableToolResultContent(modelOutput, durableEvent.retention))
                                put("is_error", durableEvent.isError)
                                durableEvent.errorCode?.let { put("error_code", it) }
                                put("retryable", durableEvent.retryable)
                                put("side_effect", durableEvent.sideEffect.name.lowercase())
                                durableEvent.recoveryHint?.let { put("recovery_hint", it) }
                            })
                            history.append(localToolHistoryMessage(durableEvent.call.id, modelOutput, durableEvent.retention))
                        }
                        is AgentEvent.StepFinished -> {
                            persistContinuationCheckpoint(step = durableEvent.step)
                        }
                        is AgentEvent.TurnCompleted -> {
                            eventLog().append("subagent/end", buildJsonObject {
                                put("agent_id", subagentId)
                                backgroundJobId?.let { put("background_job_id", it) }
                                put("status", "completed")
                                put("steps", durableEvent.steps)
                                put("answer", durableEvent.answer.take(SUBAGENT_EVENT_CHARS))
                            })
                        }
                        is AgentEvent.TurnStepLimit -> {
                            eventLog().append("subagent/end", buildJsonObject {
                                put("agent_id", subagentId)
                                backgroundJobId?.let { put("background_job_id", it) }
                                put("status", "step_limit")
                                put("steps", durableEvent.steps)
                            })
                        }
                        is AgentEvent.TurnCancelled -> {
                            eventLog().append("subagent/end", buildJsonObject {
                                put("agent_id", subagentId)
                                backgroundJobId?.let { put("background_job_id", it) }
                                put("status", "cancelled")
                            })
                        }
                        is AgentEvent.TurnFailed -> {
                            eventLog().append("subagent/end", buildJsonObject {
                                put("agent_id", subagentId)
                                backgroundJobId?.let { put("background_job_id", it) }
                                put("status", "failed")
                                put("detail", durableEvent.reason.take(2_000))
                            })
                        }
                        else -> Unit
                    }
                    runContext?.let { context ->
                        runCoordinator?.recordEvent(context, durableEvent)
                    }
                },
                maxSteps = remainingSteps,
                stepLimitExtender = AgentStepLimitExtender { currentLimit, stepsUsed ->
                    val liveBudget = historyBudget?.invoke(runProfile)
                    val durableCurrentLimit = activationStepOffset + currentLimit
                    val durableStepsUsed = activationStepOffset + stepsUsed
                    if (durableCurrentLimit >= MAX_DYNAMIC_STEPS) return@AgentStepLimitExtender null
                    if (!progressTracker.claimExtensionProgress()) {
                        eventLog().append("subagent/budget-stopped", buildJsonObject {
                            put("agent_id", subagentId)
                            put("steps_used", durableStepsUsed)
                            put("reason", "no-new-evidence")
                        })
                        return@AgentStepLimitExtender null
                    }
                    val next = nextAdaptiveAgentStepLimit(
                        currentLimit = durableCurrentLimit,
                        configuredBase = maxSteps,
                        task = task,
                        contextChars = history.encodedChars,
                        contextBudgetChars = liveBudget?.maxHistoryChars ?: state.value.kernel.contextBudgetChars,
                        pressure = resourceScheduler.snapshot().pressure,
                        kind = runKind,
                    )
                    val boundedNext = next?.coerceAtMost(MAX_DYNAMIC_STEPS)
                    if (boundedNext != null && boundedNext > durableCurrentLimit) {
                        eventLog().append("subagent/budget-extended", buildJsonObject {
                            put("agent_id", subagentId)
                            put("steps_used", durableStepsUsed)
                            put("previous_limit", durableCurrentLimit)
                            put("next_limit", boundedNext)
                            put("context_chars", history.encodedChars)
                            put("resource_pressure", resourceScheduler.snapshot().pressure.name.lowercase())
                        })
                    }
                    boundedNext?.let { it - activationStepOffset }
                },
                idFactory = { runContext?.runId ?: UUID.randomUUID().toString() },
            )

            val result = loop.run(activationInput)
            if (result.stopReason == com.labteto.dshmobile.harness.agent.AgentStopReason.COMPLETED) {
                val output = result.answer.ifBlank { "子代理已结束，但没有返回文字。" }
                backgroundJobId?.let { jobId ->
                    jobs.parkPersistent(
                        id = jobId,
                        output = output,
                        ownerSessionId = runSessionId(),
                    )
                }
                return LocalSubagentResult(
                    status = LocalSubagentStatus.COMPLETED,
                    output = output,
                )
            }

            val partial = progress.joinToString("\n")
            val output = buildString {
                append("[subagent][$subagentId][STEP_LIMIT] 动态执行预算已无法继续扩展，任务在第 $modelStep 步暂停。")
                if (partial.isNotBlank()) {
                    append("\n已完成的最近进度：\n")
                    append(partial)
                }
                append("\n已有进度已保留；正常情况下预算会自动续算直到任务完成。")
            }
            return LocalSubagentResult(LocalSubagentStatus.STEP_LIMIT, output, "STEP_LIMIT")
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            val partial = progress.joinToString("\n")
            val output = buildString {
                append("[subagent][$subagentId][TASK_CANCELLED] 子代理自身被取消；同批其他子代理不会被级联取消。")
                cancelled.message?.takeIf(String::isNotBlank)?.let { append("\n原因：$it") }
                if (partial.isNotBlank()) append("\n已完成的最近进度：\n$partial")
            }
            return LocalSubagentResult(LocalSubagentStatus.CANCELLED, output, "TASK_CANCELLED")
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
            compactionPolicy.atTurnBoundary(
                historyPolicy,
                history,
                subagentId,
                runHistoryBudget,
                runCachePolicy,
            )
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

    private fun persistentSubagentId(jobId: String): String =
        "sa-" + jobId.removePrefix("job-").take(24)

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
