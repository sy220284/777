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
    context.trim().takeIf(String::isNotEmpty)?.take(maxChars.coerceAtLeast(1))

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
    private val schemas: (Boolean) -> JsonArray,
    private val execute: suspend (LocalToolCall, Boolean) -> AgentToolResult,
    private val pruneToolResult: (String) -> String,
    private val prepareMessages: suspend (List<JsonObject>, LocalImageInputMode, String, String) -> List<JsonObject>,
    private val resolveImageMode: (LocalImageInputMode, String, String) -> LocalImageInputMode,
    private val onNativeImageAccepted: (String, String) -> Unit = { _, _ -> },
    private val onNativeImageRejected: (String, String) -> Unit = { _, _ -> },
    private val resourceScheduler: HarnessResourceScheduler,
    private val historyCompactor: LocalHistoryCompactor = LocalHistoryCompactor(),
) {
    suspend fun run(
        task: String,
        inheritHistory: Boolean,
        allowMutation: Boolean,
        backgroundJobId: String? = null,
        parentCallId: String? = null,
        modelOverride: String? = null,
        maxSteps: Int = state.value.subagentMaxSteps,
    ): String = runResult(
        task = task,
        inheritHistory = inheritHistory,
        allowMutation = allowMutation,
        backgroundJobId = backgroundJobId,
        parentCallId = parentCallId,
        modelOverride = modelOverride,
        maxSteps = maxSteps,
    ).output

    suspend fun runResult(
        task: String,
        inheritHistory: Boolean,
        allowMutation: Boolean,
        backgroundJobId: String? = null,
        parentCallId: String? = null,
        modelOverride: String? = null,
        maxSteps: Int = state.value.subagentMaxSteps,
    ): LocalSubagentResult = resourceScheduler.withResource(HarnessResourceKind.AGENT) {
        runResultWithLease(
            task = task,
            inheritHistory = inheritHistory,
            allowMutation = allowMutation,
            backgroundJobId = backgroundJobId,
            parentCallId = parentCallId,
            modelOverride = modelOverride,
            maxSteps = maxSteps,
        )
    }

    private suspend fun runResultWithLease(
        task: String,
        inheritHistory: Boolean,
        allowMutation: Boolean,
        backgroundJobId: String?,
        parentCallId: String?,
        modelOverride: String?,
        maxSteps: Int,
    ): LocalSubagentResult {
        val subagentId = "sa-" + UUID.randomUUID().toString().replace("-", "").take(12)
        val history = if (inheritHistory) {
            inheritedHistoryBeforeToolCall(historySnapshot(), parentCallId)
        } else {
            mutableListOf()
        }
        val progress = ArrayDeque<String>()
        val stepLimit = maxSteps.coerceIn(1, 128)
        val snapshot = state.value
        val routeModel = modelOverride?.trim()?.takeIf(String::isNotEmpty)?.take(120) ?: snapshot.model
        val repliesByStep = mutableMapOf<Int, LocalModelReply>()
        var modelStep = 0

        eventLog().append("subagent/start", buildJsonObject {
            put("agent_id", subagentId)
            put("background_job_id", backgroundJobId ?: "")
            put("model", routeModel)
            put("max_steps", stepLimit)
            put("task", task.take(2_000))
        })
        val key = apiKeys.get()
        if (key == null) {
            val output = "[subagent][$subagentId][NO_API_KEY] 子代理无法读取模型密钥"
            eventLog().append("subagent/end", buildJsonObject {
                put("agent_id", subagentId)
                put("status", "failed")
                put("code", "NO_API_KEY")
            })
            return LocalSubagentResult(LocalSubagentStatus.FAILED, output, "NO_API_KEY")
        }

        try {
            if (!inheritHistory) history += buildJsonObject {
                put("role", "system")
                put(
                    "content",
                    if (allowMutation) {
                        "你是安卓本机 Harness 的子代理。完成指定子任务，可使用工作区、命令、网页和技能；修改与命令仍需用户批准。"
                    } else {
                        "你是安卓本机 Harness 的只读子代理。完成指定子任务，可读取和搜索工作区、读取技能、获取网页与解析 JSON；禁止修改用户文件和执行命令。"
                    },
                )
            }
            boundedSubagentContext(contextSnapshot(task))?.let { inherited ->
                val insertion = buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        "【父级约束上下文】\n$inherited\n以上约束继承自父任务；若与本子任务的明确新要求冲突，以本子任务要求为准。",
                    )
                }
                val index = if (
                    history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system"
                ) 1 else 0
                history.add(index, insertion)
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
                    compactSubagentHistory(history, subagentId)
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
                            tools = schemas(allowMutation),
                            subagentId = subagentId,
                            step = modelStep,
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
                                tools = schemas(allowMutation),
                                subagentId = subagentId,
                                step = modelStep,
                            )
                        } else {
                            throw error
                        }
                    }
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
                    execute(call.toLocalToolCall(), allowMutation)
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
                            val boundedContent = pruneToolResult(event.output)
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
                                "第 ${event.step} 步 · ${event.call.name}：${event.output.take(1_500)}",
                            )
                            eventLog().append("subagent/tool-result", buildJsonObject {
                                put("agent_id", subagentId)
                                put("step", event.step)
                                put("id", event.call.id)
                                put("name", event.call.name)
                                put("content", event.output.take(SUBAGENT_EVENT_CHARS))
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
                },
                maxSteps = stepLimit,
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
        history: List<JsonObject>,
        tools: JsonArray,
        subagentId: String,
        step: Int,
    ): LocalModelReply {
        val executor = AgentRequestExecutor(
            maxAttempts = state.value.modelAttempts.coerceIn(1, 5),
            retryable = { error ->
                (error as? LocalModelException)?.retryable == true || error is java.io.IOException
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
        return executor.execute {
            resourceScheduler.withResource(HarnessResourceKind.MODEL_REQUEST) {
                modelClient.complete(key, baseUrl, model, history, tools)
            }
        }
    }

    private fun compactSubagentHistory(history: MutableList<JsonObject>, subagentId: String) {
        val compaction = historyCompactor.compact(history) ?: return
        history.clear()
        history += compaction.messages
        eventLog().append("subagent/compaction", buildJsonObject {
            put("agent_id", subagentId)
            put("omitted_messages", compaction.omittedMessages)
            put("summary", compaction.summary)
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
    }
}
