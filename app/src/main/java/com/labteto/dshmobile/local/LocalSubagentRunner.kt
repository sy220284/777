package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelRunContext
import kotlinx.coroutines.withContext
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.harness.agent.*
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.observability.AppLog
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

internal fun boundedSubagentContext(context: String, maxChars: Int = 10_000): String? =
    context.trim().takeIf(String::isNotEmpty)?.let {
        truncateWithoutSplittingSurrogatePair(it, maxChars.coerceAtLeast(1))
    }

internal fun inheritedHistoryBeforeToolCall(
    history: List<JsonObject>,
    parentCallId: String?,
): MutableList<JsonObject> {
    if (parentCallId.isNullOrBlank()) return history.toMutableList()
    val boundary = history.indexOfLast { message ->
        message["role"]?.jsonPrimitive?.contentOrNull == "assistant" &&
            message["tool_calls"]?.jsonArray.orEmpty().any { element ->
                element.jsonObject["id"]?.jsonPrimitive?.contentOrNull == parentCallId
            }
    }
    return if (boundary >= 0) history.take(boundary).toMutableList() else history.toMutableList()
}

internal enum class LocalSubagentStatus {
    COMPLETED,
    STEP_LIMIT,
    CANCELLED,
    FAILED,
}

internal data class LocalSubagentResult(
    val status: LocalSubagentStatus,
    val output: String,
    val errorCode: String? = null,
) {
    val succeeded: Boolean get() = status == LocalSubagentStatus.COMPLETED
}

internal fun LocalSubagentResult.requireCompletedOutput(): String {
    if (!succeeded) throw IllegalStateException(output)
    return output
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
    private val historyBudget: ((String, String) -> LocalHistoryBudget)? = null,
    private val historyCompactor: LocalHistoryCompactor = LocalHistoryCompactor(),
    private val runCoordinator: LocalAgentRunCoordinator? = null,
    private val runSessionId: () -> String = { state.value.sessionId },
    private val runKind: LocalAgentRunKind = LocalAgentRunKind.SUBAGENT,
) {
    suspend fun run(
        task: String,
        inheritHistory: Boolean,
        allowMutation: Boolean,
        backgroundJobId: String? = null,
        parentCallId: String? = null,
        modelOverride: String? = null,
        maxSteps: Int = state.value.subagentMaxSteps,
        virtualScreen: Boolean = false,
    ): String = runResult(
        task = task,
        inheritHistory = inheritHistory,
        allowMutation = allowMutation,
        backgroundJobId = backgroundJobId,
        parentCallId = parentCallId,
        modelOverride = modelOverride,
        maxSteps = maxSteps,
        virtualScreen = virtualScreen,
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
    ): LocalSubagentResult {
        val subagentId = "sa-" + UUID.randomUUID().toString().replace("-", "").take(12)
        val history = if (inheritHistory) {
            inheritedHistoryBeforeToolCall(historySnapshot(), parentCallId)
        } else {
            mutableListOf()
        }
        val progress = ArrayDeque<String>()
        val runProfile = try { modelGateway.profileForRun(modelOverride) } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return LocalSubagentResult(LocalSubagentStatus.FAILED,
                "[subagent][$subagentId] ${error.message}", (error as? LocalModelException)?.code ?: "NO_MODEL_CREDENTIAL")
        }
        val snapshot = state.value.copy(model = runProfile.model, baseUrl = runProfile.baseUrl)
        val routeModel = runProfile.model
        val runHistoryBudget = historyBudget?.invoke(snapshot.baseUrl, routeModel)
        val stepLimit = adaptiveAgentStepLimit(
            configuredBase = maxSteps,
            task = task,
            contextChars = history.sumOf { it.toString().length } + task.length,
            contextBudgetChars = runHistoryBudget?.maxHistoryChars ?: snapshot.contextBudgetChars,
            pressure = resourceScheduler.snapshot().pressure,
            kind = runKind,
        )
        val repliesByStep = mutableMapOf<Int, LocalModelReply>()
        var modelStep = 0
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
        val key = ""
        val parentRunId = parentCallId?.let { callId ->
            eventLog().events()
                .filter { event ->
                    event.type == LOCAL_AGENT_RUN_CHECKPOINT_EVENT &&
                        event.data["call_id"]?.jsonPrimitive?.contentOrNull == callId
                }
                .mapNotNull { event -> event.data["run_id"]?.jsonPrimitive?.contentOrNull }
                .lastOrNull()
        }
        val runContext = runCoordinator?.start(
            sessionId = runSessionId(),
            usageMode = LocalUsageMode.WORK,
            model = routeModel,
            baseUrl = snapshot.baseUrl,
            planMode = snapshot.planMode,
            policy = localAgentRunPolicy(LocalUsageMode.WORK),
            safeAutoApprovalEnabled = snapshot.safeAutoApprovalEnabled,
            maxSteps = stepLimit,
            input = task,
            memoryInput = task,
            kind = runKind,
            allowMutation = allowMutation,
            resourceBudget = LocalAgentRunResourceBudget(
                maxModelRequests = snapshot.maxModelRequests,
                maxAgents = snapshot.maxAgents,
                maxTerminals = snapshot.maxTerminals,
                maxVirtualDisplays = snapshot.maxVirtualDisplays,
                maxLanguageServers = snapshot.maxLanguageServers,
            ),
            toolNames = schemas(
                allowMutation,
                virtualScreenId != null,
                enabledOptionalTools,
            ).mapNotNull { element ->
                val function = (element as? JsonObject)?.get("function") as? JsonObject
                (function?.get("name") as? JsonPrimitive)?.content
            },
            contextChars = history.sumOf { it.toString().length } + task.length,
            parentRunId = parentRunId,
            agentId = subagentId,
        )

        try {
            if (!inheritHistory) history += buildJsonObject {
                put("role", "system")
                put(
                    "content",
                    if (allowMutation) {
                        "你是执行子代理。完成指定子任务，按权限使用可用能力，并核实结果后返回。"
                    } else {
                        "你是只读子代理。完成指定子任务；仅允许读取、搜索和分析，不修改状态。"
                    },
                )
            }
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
                history.add(index, insertion)
            }
            virtualScreenId?.let { id ->
                history += buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        "【独立虚拟屏】id=$id；界面操作仅用 android_vscreen_* 并传入该 id，禁止操作主屏。",
                    )
                }
            }
            history += buildJsonObject { put("role", "user"); put("content", task) }

            val loop = AgentLoop(
                model = AgentModel {
                    backgroundJobId?.let(jobs::drainMessages).orEmpty().forEach { message ->
                        history += buildJsonObject {
                            put("role", "user")
                            put("content", message)
                        }
                    }
                    compactSubagentHistory(history, subagentId, runHistoryBudget)
                    modelStep += 1
                    val durableHistory = history.toList()
                    val selectedMode = resolveImageMode(snapshot.imageInputMode, snapshot.baseUrl, routeModel)
                    if (hasLocalImageRefs(durableHistory) &&
                        resolveImageMode(LocalImageInputMode.AUTO, snapshot.baseUrl, routeModel) == LocalImageInputMode.TOOL) {
                        throw IllegalStateException("当前模型不支持图片理解，请切换支持图片的模型后重试。")
                    }
                    val preparedHistory = prepareMessages(
                        durableHistory,
                        selectedMode,
                        snapshot.baseUrl,
                        routeModel,
                    )
                    val nativeImagesSent = hasMaterializedImageUrls(preparedHistory)
                    val reply = try {
                        completeSubagentStep(
                            profile = runProfile,
                            key = key,
                            baseUrl = snapshot.baseUrl,
                            model = routeModel,
                            history = preparedHistory,
                            tools = schemas(allowMutation, virtualScreenId != null, enabledOptionalTools),
                            subagentId = subagentId,
                            step = modelStep,
                            durableHistory = history,
                        ).also {
                            if (nativeImagesSent) {
                                onNativeImageAccepted(snapshot.baseUrl, routeModel)
                            }
                        }
                    } catch (error: Throwable) {
                        val nativeImageRejected =
                            nativeImagesSent &&
                                imageInputUnsupported(error)
                        if (nativeImageRejected) {
                            onNativeImageRejected(snapshot.baseUrl, routeModel)
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
                    val virtualAllowed = virtualScreenId != null && call.name in SUBAGENT_VIRTUAL_SCREEN_TOOLS
                    val requestedScreen = call.arguments["id"]?.jsonPrimitive?.contentOrNull
                    when {
                        !allowMutation && call.name in SUBAGENT_VIRTUAL_SCREEN_TOOLS && !virtualAllowed ->
                            AgentToolResult(
                                content = "子代理没有可用的虚拟屏租约",
                                isError = true,
                                errorCode = "SUBAGENT_VIRTUAL_SCREEN_REQUIRED",
                                recoveryHint = "重新以 virtual_screen=true 启动该子任务。",
                            )
                        !allowMutation && call.name.startsWith("android_") && !virtualAllowed ->
                            AgentToolResult(
                                content = "只读子代理未获主屏设备操作权限",
                                isError = true,
                                errorCode = "SUBAGENT_DEVICE_SCOPE_BLOCKED",
                                recoveryHint = "仅使用已分配虚拟屏的受限工具。",
                            )
                        virtualAllowed && requestedScreen != virtualScreenId ->
                            AgentToolResult(
                                content = "子代理只能操作自己分配的虚拟屏",
                                isError = true,
                                errorCode = "SUBAGENT_VIRTUAL_SCREEN_MISMATCH",
                                recoveryHint = "使用系统上下文中提供的虚拟屏 id。",
                            )
                        else -> withContext(LocalModelRunContext(runProfile)) {
                            execute(call.toLocalToolCall(), allowMutation, enabledOptionalTools)
                        }
                    }
                },
                eventSink = AgentEventSink { event ->
                    when (event) {
                        is AgentEvent.AssistantObserved -> {
                            val reply = repliesByStep.remove(event.step)
                                ?: error("缺少子代理第 ${event.step} 步模型响应")
                            history += reply.message
                            reply.content?.takeIf(String::isNotBlank)?.let { content ->
                                rememberSubagentProgress(
                                    progress,
                                    "第 ${event.step} 步回复：${content.take(1_500)}",
                                )
                            }
                        }
                        is AgentEvent.ToolStarted -> {
                            eventLog().append("subagent/tool-call", buildJsonObject {
                                put("agent_id", subagentId)
                                put("step", event.step)
                                put("id", event.call.id)
                                put("name", event.call.name)
                                put("arguments", event.call.arguments)
                            })
                        }
                        is AgentEvent.ToolFinished -> {
                            val boundedContent = retainSubagentToolResult(
                                event.call.id,
                                event.output,
                                runHistoryBudget,
                                history,
                            )
                            val modelOutput = AgentToolResult(
                                content = boundedContent,
                                isError = event.isError,
                                errorCode = event.errorCode,
                                retryable = event.retryable,
                                sideEffect = event.sideEffect,
                                recoveryHint = event.recoveryHint,
                            ).modelVisibleContent()
                            rememberSubagentProgress(
                                progress,
                                "第 ${event.step} 步 · ${event.call.name}：" +
                                    truncateWithoutSplittingSurrogatePair(event.output, 1_500),
                            )
                            eventLog().append("subagent/tool-result", buildJsonObject {
                                put("agent_id", subagentId)
                                put("step", event.step)
                                put("id", event.call.id)
                                put("name", event.call.name)
                                put(
                                    "content",
                                    truncateWithoutSplittingSurrogatePair(event.output, SUBAGENT_EVENT_CHARS),
                                )
                                put("model_content", modelOutput)
                                put("is_error", event.isError)
                                event.errorCode?.let { put("error_code", it) }
                                put("retryable", event.retryable)
                                put("side_effect", event.sideEffect.name.lowercase())
                                event.recoveryHint?.let { put("recovery_hint", it) }
                            })
                            history += buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", event.call.id)
                                put("content", modelOutput)
                            }
                        }
                        is AgentEvent.TurnCompleted -> {
                            eventLog().append("subagent/end", buildJsonObject {
                                put("agent_id", subagentId)
                                put("status", "completed")
                                put("steps", event.steps)
                            })
                        }
                        is AgentEvent.TurnStepLimit -> {
                            eventLog().append("subagent/end", buildJsonObject {
                                put("agent_id", subagentId)
                                put("status", "step_limit")
                                put("steps", event.steps)
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
                        runCoordinator?.recordEvent(context, event)
                    }
                },
                maxSteps = stepLimit,
                stepLimitExtender = AgentStepLimitExtender { currentLimit, stepsUsed ->
                    val liveBudget = historyBudget?.invoke(snapshot.baseUrl, routeModel)
                    if (currentLimit >= MAX_DYNAMIC_STEPS) return@AgentStepLimitExtender null
                    val next = nextAdaptiveAgentStepLimit(
                        currentLimit = currentLimit,
                        configuredBase = maxSteps,
                        task = task,
                        contextChars = history.sumOf { it.toString().length },
                        contextBudgetChars = liveBudget?.maxHistoryChars ?: state.value.contextBudgetChars,
                        pressure = resourceScheduler.snapshot().pressure,
                        kind = runKind,
                    )
                    val boundedNext = next?.coerceAtMost(MAX_DYNAMIC_STEPS)
                    if (boundedNext != null && boundedNext > currentLimit) {
                        eventLog().append("subagent/budget-extended", buildJsonObject {
                            put("agent_id", subagentId)
                            put("steps_used", stepsUsed)
                            put("previous_limit", currentLimit)
                            put("next_limit", boundedNext)
                            put("context_chars", history.sumOf { it.toString().length })
                            put("resource_pressure", resourceScheduler.snapshot().pressure.name.lowercase())
                        })
                    }
                    boundedNext
                },
                idFactory = { runContext?.runId ?: UUID.randomUUID().toString() },
            )

            val result = loop.run(task)
            if (result.stopReason == com.labteto.dshmobile.harness.agent.AgentStopReason.COMPLETED) {
                return LocalSubagentResult(
                    status = LocalSubagentStatus.COMPLETED,
                    output = result.answer.ifBlank { "子代理已结束，但没有返回文字。" },
                )
            }

            val partial = progress.joinToString("\n")
            val output = buildString {
                append("[subagent][$subagentId][STEP_LIMIT] 动态执行预算已无法继续扩展，任务在第 ${result.steps} 步暂停。")
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
            return LocalSubagentResult(LocalSubagentStatus.FAILED, output, error.code)
        } catch (error: Exception) {
            val partial = progress.joinToString("\n")
            val output = buildString {
                append("[subagent][$subagentId][SUBAGENT_ERROR] ${error.message ?: error::class.java.simpleName}")
                if (partial.isNotBlank()) append("\n已完成的最近进度：\n$partial")
            }
            return LocalSubagentResult(LocalSubagentStatus.FAILED, output, "SUBAGENT_ERROR")
        }
    }

    private suspend fun completeSubagentStep(
        profile: com.labteto.dshmobile.local.LocalModelProfile,
        key: String,
        baseUrl: String,
        model: String,
        history: List<JsonObject>,
        tools: JsonArray,
        subagentId: String,
        step: Int,
        durableHistory: MutableList<JsonObject>? = null,
        allowContextOverflowRecovery: Boolean = true,
    ): LocalModelReply {
        val executor = AgentRequestExecutor(
            maxAttempts = state.value.modelAttempts.coerceIn(1, 5),
            retryable = { error ->
                (error as? LocalModelException)?.retryable == true || error is java.io.IOException
            },
            backoffMillis = { failedAttempt, error ->
                (error as? LocalModelException)?.providerRetryAfterMs
                    ?.coerceIn(0L, 60_000L)
                    ?: (1_000L shl (failedAttempt - 1).coerceIn(0, 20))
            },
            eventSink = AgentRequestEventSink { event ->
                when (event) {
                    is AgentRequestEvent.AttemptStarted -> {
                        eventLog().append("subagent/request", buildJsonObject {
                            put("agent_id", subagentId)
                            put("step", step)
                            put("attempt", event.attempt)
                            put("max_attempts", event.maxAttempts)
                        })
                    }
                    is AgentRequestEvent.AttemptFailed -> {
                        eventLog().append("subagent/request-error", buildJsonObject {
                            put("agent_id", subagentId)
                            put("step", step)
                            put("attempt", event.attempt)
                            put("retryable", event.retryable)
                            put("will_retry", event.willRetry)
                            put("detail", event.reason.take(2_000))
                        })
                    }
                    is AgentRequestEvent.RetryScheduled -> {
                        eventLog().append("subagent/retry", buildJsonObject {
                            put("agent_id", subagentId)
                            put("step", step)
                            put("attempt", event.attempt)
                            put("next_attempt", event.nextAttempt)
                            put("delay_ms", event.delayMillis)
                        })
                    }
                    is AgentRequestEvent.AttemptCancelled -> {
                        eventLog().append("subagent/request-cancelled", buildJsonObject {
                            put("agent_id", subagentId)
                            put("step", step)
                            put("attempt", event.attempt)
                            event.reason?.let { put("detail", it.take(2_000)) }
                        })
                    }
                    is AgentRequestEvent.AttemptSucceeded -> Unit
                }
            },
        )
        var activeHistory = history
        var overflowRound = 0
        while (true) {
            try {
                return executor.execute {
                    resourceScheduler.withResource(HarnessResourceKind.MODEL_REQUEST) {
                        try {
                            modelGateway.complete(
                                profile = profile,
                                model = model,
                                baseUrl = baseUrl,
                                messages = activeHistory,
                                tools = tools,
                            )
                        } catch (error: LocalModelException) {
                            eventLog().append("subagent/provider-error", buildJsonObject {
                                put("agent_id", subagentId)
                                put("step", step)
                                put("code", error.code)
                                error.status?.let { put("status", it) }
                                error.providerRetryAfterMs?.let { put("retry_after_ms", it) }
                                error.requestId?.let { put("request_id", it) }
                                error.providerCode?.let { put("provider_code", it) }
                                error.providerParam?.let { put("provider_param", it) }
                                error.cause?.let { cause ->
                                    put("cause_type", cause::class.java.simpleName)
                                    cause.message?.takeIf(String::isNotBlank)?.let {
                                        put("cause_detail", it.take(800))
                                    }
                                }
                            })
                            throw error
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!allowContextOverflowRecovery || !contextWindowExceeded(error)) throw error
                val compacted = historyCompactor.compactForOverflow(
                    activeHistory,
                    LocalHistorySummaryMode.WORK,
                ) ?: throw error
                val madeProgress =
                    compacted.estimatedTokensAfter < compacted.estimatedTokensBefore &&
                        compacted.messages != activeHistory
                if (!madeProgress) throw error

                overflowRound += 1
                durableHistory?.let { durable ->
                    applyOverflowCompaction(
                        history = durable,
                        compactor = historyCompactor,
                        summaryMode = LocalHistorySummaryMode.WORK,
                    )?.let { durableCompaction ->
                        eventLog().append("subagent/compaction", buildJsonObject {
                            put("agent_id", subagentId)
                            put("trigger", "context-overflow")
                            put("round", overflowRound)
                            put("omitted_messages", durableCompaction.omittedMessages)
                            put("summary", durableCompaction.summary)
                            put("estimated_tokens_before", durableCompaction.estimatedTokensBefore)
                            put("estimated_tokens_after", durableCompaction.estimatedTokensAfter)
                        })
                    }
                }
                eventLog().append("subagent/context-overflow-recovery", buildJsonObject {
                    put("agent_id", subagentId)
                    put("step", step)
                    put("round", overflowRound)
                    put("model", model)
                    put("estimated_tokens_before", compacted.estimatedTokensBefore)
                    put("estimated_tokens_after", compacted.estimatedTokensAfter)
                    put("omitted_messages", compacted.omittedMessages)
                })
                activeHistory = compacted.messages
            }
        }
    }

    private fun retainSubagentToolResult(
        callId: String,
        output: String,
        budget: LocalHistoryBudget?,
        history: List<JsonObject>,
    ): String {
        budget ?: return output
        val adaptiveBudget = adaptiveToolResultBudget(
            base = budget,
            currentHistoryChars = history.sumOf { it.toString().length },
            currentHistoryTokens = history.sumOf { estimateModelTokens(it.toString()) },
        )
        val retained = retainTextForModel(
            value = output,
            maxTokens = adaptiveBudget.maxToolResultTokens,
            maxChars = adaptiveBudget.maxToolResultChars,
        )
        if (!retained.truncated) return retained.text
        val stored = spillToolOutput(callId, output)
        val recovery = if (stored) {
            "可调用 tool_output_read，并传入 call_id=$callId 分段读取完整结果。"
        } else {
            "完整结果超过本机私有保留上限；请缩小原查询后重试。"
        }
        return retained.text +
            "\n[已从模型上下文省略 ${retained.omittedBytes} 个 UTF-8 字节；$recovery]"
    }

    private fun compactSubagentHistory(
        history: MutableList<JsonObject>,
        subagentId: String,
        budget: LocalHistoryBudget?,
    ) {
        val compaction = historyCompactor.compact(history, budget) ?: return
        history.clear()
        history += compaction.messages
        eventLog().append("subagent/compaction", buildJsonObject {
            put("agent_id", subagentId)
            put("omitted_messages", compaction.omittedMessages)
            put("summary", compaction.summary)
            put("estimated_tokens_before", compaction.estimatedTokensBefore)
            put("estimated_tokens_after", compaction.estimatedTokensAfter)
        })
    }

    private fun rememberSubagentProgress(progress: ArrayDeque<String>, item: String) {
        progress.addLast(item)
        while (progress.size > SUBAGENT_PROGRESS_ITEMS) progress.removeFirst()
    }


    private fun AgentToolCall.toLocalToolCall() = LocalToolCall(id, name, arguments, rawArguments)
    private companion object {
        const val SUBAGENT_PROGRESS_ITEMS = 6
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
