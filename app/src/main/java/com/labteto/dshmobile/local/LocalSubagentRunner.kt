package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

internal fun boundedSubagentContext(context: String, maxChars: Int = 10_000): String? =
    context.trim().takeIf(String::isNotEmpty)?.take(maxChars.coerceAtLeast(1))

internal class LocalSubagentRunner(
    private val apiKeys: LocalApiKeyStore,
    private val modelClient: DeepSeekClient,
    private val state: StateFlow<LocalHarnessState>,
    private val jobs: LocalJobManager,
    private val historySnapshot: () -> List<JsonObject>,
    private val contextSnapshot: (String) -> String,
    private val eventLog: () -> LocalSessionEventLog,
    private val schemas: (Boolean) -> JsonArray,
    private val execute: suspend (LocalToolCall, Boolean) -> String,
    private val pruneToolResult: (String) -> String,
) {
    suspend fun run(
        task: String,
        inheritHistory: Boolean,
        allowMutation: Boolean,
        backgroundJobId: String? = null,
        modelOverride: String? = null,
        maxSteps: Int = state.value.subagentMaxSteps,
    ): String {
        val subagentId = "sa-" + UUID.randomUUID().toString().replace("-", "").take(12)
        val key = apiKeys.get() ?: return "[subagent][$subagentId][NO_API_KEY] 子代理无法读取模型密钥"
        val history = if (inheritHistory) historySnapshot().toMutableList() else mutableListOf()
        val progress = ArrayDeque<String>()
        val stepLimit = maxSteps.coerceIn(1, 40)
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
                history += buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        "【父级约束上下文】\n$inherited\n以上约束继承自父任务；若与本子任务的明确新要求冲突，以本子任务要求为准。",
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
                    modelStep += 1
                    val reply = completeSubagentStep(
                        key = key,
                        baseUrl = snapshot.baseUrl,
                        model = routeModel,
                        history = history.toList(),
                        tools = schemas(allowMutation),
                        subagentId = subagentId,
                        step = modelStep,
                    )
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
                        is AgentEvent.ToolFinished -> {
                            rememberSubagentProgress(
                                progress,
                                "第 ${event.step} 步 · ${event.call.name}：${event.output.take(1_500)}",
                            )
                            history += buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", event.call.id)
                                put("content", pruneToolResult(event.output))
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
                return result.answer.ifBlank { "子代理已结束，但没有返回文字。" }
            }

            val partial = progress.joinToString("\n")
            return buildString {
                append("[subagent][$subagentId][STEP_LIMIT] 达到 $stepLimit 步上限，任务未完整结束。")
                if (partial.isNotBlank()) {
                    append("\n已完成的最近进度：\n")
                    append(partial)
                }
                append("\n建议：继续任务时可把 max_steps 调高，当前允许最高 40。")
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            val partial = progress.joinToString("\n")
            return buildString {
                append("[subagent][$subagentId][TASK_CANCELLED] 子代理自身被取消；同批其他子代理不会被级联取消。")
                cancelled.message?.takeIf(String::isNotBlank)?.let { append("\n原因：$it") }
                if (partial.isNotBlank()) append("\n已完成的最近进度：\n$partial")
            }
        } catch (error: LocalModelException) {
            val partial = progress.joinToString("\n")
            return buildString {
                append("[subagent][$subagentId][${error.code}] 模型阶段失败：${error.message}")
                if (partial.isNotBlank()) append("\n已完成的最近进度：\n$partial")
                append("\n建议：模型超时可重试；网页/工具超时请查看对应工具错误码。")
            }
        } catch (error: Exception) {
            val partial = progress.joinToString("\n")
            return buildString {
                append("[subagent][$subagentId][SUBAGENT_ERROR] ${error.message ?: error::class.java.simpleName}")
                if (partial.isNotBlank()) append("\n已完成的最近进度：\n$partial")
            }
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
            modelClient.complete(key, baseUrl, model, history, tools)
        }
    }

    private fun rememberSubagentProgress(progress: ArrayDeque<String>, item: String) {
        progress.addLast(item)
        while (progress.size > SUBAGENT_PROGRESS_ITEMS) progress.removeFirst()
    }


    private fun AgentToolCall.toLocalToolCall() = LocalToolCall(id, name, arguments, rawArguments)
    private companion object { const val SUBAGENT_PROGRESS_ITEMS = 6 }
}
