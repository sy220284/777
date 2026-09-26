package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.*
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
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
    private val apiKeys: LocalApiKeyStore,
    private val modelClient: DeepSeekClient,
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
    private val onUsage: (String, DeepSeekTokenUsage) -> Unit = { _, _ -> },
    private val onNativeImageAccepted: (String, String) -> Unit = { _, _ -> },
    private val onNativeImageRejected: (String, String) -> Unit = { _, _ -> },
    private val resourceScheduler: HarnessResourceScheduler,
    private val acquireVirtualScreen: suspend (String) -> String? = { null },
    private val releaseVirtualScreen: (String) -> Unit = { },
    private val historyBudget: ((String, String) -> LocalHistoryBudget)? = null,
    private val historyCompactor: LocalHistoryCompactor = LocalHistoryCompactor(),
    private val runInterceptors: () -> List<AgentRunInterceptor> = { emptyList() },
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
        continuationHistory: List<JsonObject>? = null,
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
                continuationHistory = continuationHistory,
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
        continuationHistory: List<JsonObject>?,
    ): LocalSubagentResult {
        val subagentId = backgroundJobId
            ?: ("sa-" + UUID.randomUUID().toString().replace("-", "").take(12))
        val history = when {
            !continuationHistory.isNullOrEmpty() -> continuationHistory.toMutableList()
            inheritHistory -> inheritedHistoryBeforeToolCall(historySnapshot(), parentCallId)
            else -> mutableListOf()
        }
        val progress = ArrayDeque<String>()
        val stepLimit = maxSteps.coerceIn(1, 128)
        val snapshot = state.value
        val routeModel = modelOverride?.trim()?.takeIf(String::isNotEmpty)?.take(120) ?: snapshot.model
        val runHistoryBudget = historyBudget?.invoke(snapshot.baseUrl, routeModel)
        val repliesByStep = mutableMapOf<Int, LocalModelReply>()
        var modelStep = 0
        // Optional tool visibility belongs to this exact Agent run. A child discovering an MCP/LSP/
        // runtime capability must never make that capability appear in its parent or sibling run.
        val enabledOptionalTools = linkedSetOf<String>()

        fun checkpoint(reason: String) {
            if (backgroundJobId == null || history.isEmpty()) return
            eventLog().append(
                LOCAL_SUBAGENT_CHECKPOINT_EVENT,
                encodeSubagentContinuationCheckpoint(
                    agentId = subagentId,
                    model = routeModel,
                    maxSteps = stepLimit,
                    allowMutation = allowMutation,
                    virtualScreen = virtualScreenId != null,
                    history = history,
                    compactor = historyCompactor,
                ) + ("reason" to JsonPrimitive(reason)),
            )
        }

        fun archive(status: String, detail: String? = null) {
            eventLog().append("subagent/archive", buildJsonObject {
                put("agent_id", subagentId)
                put("parent_session_id", snapshot.sessionId)
                put("lineage_id", snapshot.lineageId)
                put("depth", 1)
                put("durable", backgroundJobId != null)
                put("status", status)
                detail?.takeIf(String::isNotBlank)?.let { put("detail", it.take(2_000)) }
            })
        }

        eventLog().append("subagent/start", buildJsonObject {
            put("agent_id", subagentId)
            put("background_job_id", backgroundJobId ?: "")
            put("parent_session_id", snapshot.sessionId)
            put("lineage_id", snapshot.lineageId)
            put("depth", 1)
            put("model", routeModel)
            put("max_steps", stepLimit)
            put("task", task.take(2_000))
            virtualScreenId?.let { put("virtual_screen_id", it) }
        })
        val key = apiKeys.get()
        if (key == null) {
            val output = "[subagent][$subagentId][NO_API_KEY] 子代理无法读取模型密钥"
            eventLog().append("subagent/end", buildJsonObject {
                put("agent_id", subagentId)
                put("status", "failed")
                put("code", "NO_API_KEY")
            })
            archive("failed", "NO_API_KEY")
            return LocalSubagentResult(LocalSubagentStatus.FAILED, output, "NO_API_KEY")
        }

        try {
            if (continuationHistory.isNullOrEmpty() && !inheritHistory) history += buildJsonObject {
                put("role", "system")
                put(
                    "content",
                    if (allowMutation) {
                        "你是本机子代理。完成指定子任务；可使用工作区、命令、网页和技能，修改或执行命令按权限审批。"
                    } else {
                        "你是本机只读子代理。完成指定子任务；可读取和搜索工作区、技能与网页，禁止修改文件或执行命令。"
                    },
                )
            }
            if (continuationHistory.isNullOrEmpty()) boundedSubagentContext(contextSnapshot(task))?.let { inherited ->
                val insertion = buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        "【父任务约束】\n$inherited\n遵守以上约束；若本子任务有明确更新，以本子任务为准。",
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
                        "【独立虚拟屏】已分配 id=$id。操作界面时只用 android_vscreen_* 并传入该 id；不要操作主屏。",
                    )
                }
            }
            history += buildJsonObject { put("role", "user"); put("content", task) }
            checkpoint(if (continuationHistory.isNullOrEmpty()) "initial" else "continued")

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
                    val preparedHistory = prepareMessages(
                        durableHistory,
                        selectedMode,
                        snapshot.baseUrl,
                        routeModel,
                    )
                    val nativeImagesSent = hasMaterializedImageUrls(preparedHistory)
                    val reply = try {
                        completeSubagentStep(
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
                        if (snapshot.imageInputMode == LocalImageInputMode.AUTO && nativeImageRejected) {
                            eventLog().append("subagent/multimodal-fallback", buildJsonObject {
                                put("agent_id", subagentId)
                                put("step", modelStep)
                                put("model", routeModel)
                                put("from", "native")
                                put("to", "vision-tool")
                                put("reason", error.message.orEmpty().take(2_000))
                            })
                            completeSubagentStep(
                                key = key,
                                baseUrl = snapshot.baseUrl,
                                model = routeModel,
                                history = prepareMessages(
                                    durableHistory,
                                    LocalImageInputMode.TOOL,
                                    snapshot.baseUrl,
                                    routeModel,
                                ),
                                tools = schemas(allowMutation, virtualScreenId != null, enabledOptionalTools),
                                subagentId = subagentId,
                                step = modelStep,
                                durableHistory = history,
                            )
                        } else {
                            throw error
                        }
                    }
                    onUsage(routeModel, reply.usage)
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
                        else -> execute(call.toLocalToolCall(), allowMutation, enabledOptionalTools)
                    }
                },
                eventSink = AgentEventSink { event ->
                    when (event) {
                        is AgentEvent.AssistantObserved -> {
                            val reply = repliesByStep.remove(event.step)
                                ?: error("缺少子代理第 ${event.step} 步模型响应")
                            history += reply.message
                            checkpoint("assistant")
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
                            checkpoint("tool-result")
                        }
                        is AgentEvent.TurnCompleted -> {
                            eventLog().append("subagent/end", buildJsonObject {
                                put("agent_id", subagentId)
                                put("status", "completed")
                                put("steps", event.steps)
                            })
                            checkpoint("completed")
                            archive("completed")
                        }
                        is AgentEvent.TurnStepLimit -> {
                            eventLog().append("subagent/end", buildJsonObject {
                                put("agent_id", subagentId)
                                put("status", "step_limit")
                                put("steps", event.steps)
                            })
                            checkpoint("step-limit")
                            archive("step_limit")
                        }
                        is AgentEvent.TurnCancelled -> {
                            eventLog().append("subagent/end", buildJsonObject {
                                put("agent_id", subagentId)
                                put("status", "cancelled")
                            })
                            checkpoint("cancelled")
                            archive("cancelled")
                        }
                        is AgentEvent.TurnFailed -> {
                            eventLog().append("subagent/end", buildJsonObject {
                                put("agent_id", subagentId)
                                put("status", "failed")
                                put("detail", event.reason.take(2_000))
                            })
                            checkpoint("failed")
                            archive("failed", event.reason)
                        }
                        else -> Unit
                    }
                },
                runInterceptors = runInterceptors(),
                maxSteps = stepLimit,
            )

            val result = loop.run(
                task,
                context = AgentRunContext(
                    runId = subagentId,
                    sessionId = snapshot.sessionId,
                    lineageId = snapshot.lineageId,
                    parentRunId = parentCallId,
                    depth = 1,
                    modelRoute = AgentModelRoute(
                        provider = if (snapshot.baseUrl.contains("api.deepseek.com")) "deepseek" else "openai-compatible",
                        baseUrl = snapshot.baseUrl,
                        model = routeModel,
                        protocol = snapshot.modelProtocol,
                    ),
                    permissions = AgentPermissionScope(
                        allowMutation = allowMutation,
                        approvalScope = "subagent",
                    ),
                    resources = AgentResourceBudget(maxSteps = stepLimit, maxDepth = 4),
                    attributes = mapOf(
                        "kind" to "subagent",
                        "background" to (backgroundJobId != null).toString(),
                    ),
                ),
            )
            if (result.stopReason == com.labteto.dshmobile.harness.agent.AgentStopReason.COMPLETED) {
                return LocalSubagentResult(
                    status = LocalSubagentStatus.COMPLETED,
                    output = result.answer.ifBlank { "子代理已结束，但没有返回文字。" },
                )
            }

            val partial = progress.joinToString("\n")
            val output = buildString {
                append("[subagent][$subagentId][STEP_LIMIT] 达到 $stepLimit 步上限，任务未完整结束。")
                if (partial.isNotBlank()) {
                    append("\n已完成的最近进度：\n")
                    append(partial)
                }
                append("\n建议：继续任务时可把 max_steps 调高，当前允许最高 128。")
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
        key: String,
        baseUrl: String,
        model: String,
        protocol: AgentModelProtocol,
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
            providerRetryDelayMillis = { error, _ ->
                (error as? LocalModelException)?.retryAfterMillis
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
        return try {
            executor.execute {
                resourceScheduler.withResource(HarnessResourceKind.MODEL_REQUEST) {
                    modelClient.complete(
                        apiKey = key,
                        baseUrl = baseUrl,
                        model = model,
                        messages = history,
                        tools = tools,
                        protocol = protocol,
                    )
                }
            }
        } catch (error: Throwable) {
            if (!allowContextOverflowRecovery || !contextWindowExceeded(error)) throw error
            val compacted = historyCompactor.compactForOverflow(
                history,
                LocalHistorySummaryMode.WORK,
            ) ?: throw error
            durableHistory?.let { durable ->
                applyOverflowCompaction(
                    history = durable,
                    compactor = historyCompactor,
                    summaryMode = LocalHistorySummaryMode.WORK,
                )?.let { durableCompaction ->
                    eventLog().append("subagent/compaction", buildJsonObject {
                        put("agent_id", subagentId)
                        put("trigger", "context-overflow")
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
                put("model", model)
                put("estimated_tokens_before", compacted.estimatedTokensBefore)
                put("estimated_tokens_after", compacted.estimatedTokensAfter)
                put("omitted_messages", compacted.omittedMessages)
            })
            completeSubagentStep(
                key = key,
                baseUrl = baseUrl,
                model = model,
                protocol = protocol,
                history = compacted.messages,
                tools = tools,
                subagentId = subagentId,
                step = step,
                durableHistory = null,
                allowContextOverflowRecovery = false,
            )
        }
    }

    private fun retainSubagentToolResult(
        callId: String,
        output: String,
        budget: LocalHistoryBudget?,
    ): String {
        budget ?: return output
        val retained = retainTextForModel(
            value = output,
            maxTokens = budget.maxToolResultTokens,
            maxChars = budget.maxToolResultChars,
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
