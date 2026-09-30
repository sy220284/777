package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentRequestEvent
import com.labteto.dshmobile.harness.agent.AgentRequestEventSink
import com.labteto.dshmobile.harness.agent.AgentRequestExecutor
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalStreamingPreviewStore
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Owns foreground model transport semantics: request logging, retry, streaming preview and overflow
 * recovery. The engine supplies only current product state and the durable compaction callback.
 */
internal class LocalModelRequestCoordinator(
    private val modelGateway: LocalModelGateway,
    private val resourceScheduler: HarnessResourceScheduler,
    private val historyCompactor: LocalHistoryCompactor,
    private val toolSchemas: (LocalAgentRunPolicy) -> JsonArray,
    private val defaultEventLog: () -> LocalSessionEventLog,
    private val streamingPreviewStore: LocalStreamingPreviewStore,
    private val persistOverflowCompaction: (LocalHarnessState, LocalHistorySummaryMode) -> Unit,
    private val maxStreamPreviewChars: Int = 4_096,
    private val streamPreviewIntervalMs: Long = 50L,
) {
    suspend fun complete(
        snapshot: LocalHarnessState,
        messages: List<JsonObject>,
        step: Int,
        toolsOverride: JsonArray? = null,
        publishPreviewEnabled: Boolean = true,
        maxAttemptsOverride: Int? = null,
        allowContextOverflowRecovery: Boolean = true,
        persistOverflowHistory: Boolean = false,
        streamFilterPhrases: List<String> = emptyList(),
        requestLog: LocalSessionEventLog? = null,
        temperature: Double? = null,
        previewGuard: () -> Boolean = { true },
        overflowPersister: ((LocalHarnessState, LocalHistorySummaryMode) -> Unit)? = null,
    ): LocalModelReply {
        val tools = toolsOverride ?: toolSchemas(localAgentRunPolicy(snapshot.usageMode))
        val previewOwner = if (publishPreviewEnabled) {
            streamingPreviewStore.newOwner(
                sessionId = snapshot.sessionId,
                requestId = UUID.randomUUID().toString(),
                usageMode = snapshot.usageMode,
            )
        } else {
            null
        }
        val log = requestLog ?: defaultEventLog()
        val logMessages = redactModelImages(messages)
        val contextChars = logMessages.sumOf { it.toString().length }
        val toolNames = buildJsonArray {
            tools.forEach { element ->
                val function = (element as? JsonObject)?.get("function") as? JsonObject
                function?.get("name")?.jsonPrimitive?.contentOrNull?.let { name ->
                    add(JsonPrimitive(name))
                }
            }
        }
        log.append("request/header", buildJsonObject {
            put("model", snapshot.model)
            put("base_url", snapshot.baseUrl)
            put("step", step)
            put("message_count", logMessages.size)
            put("context_chars", contextChars)
            put("tool_count", tools.size)
            put("tool_names", toolNames)
            put("plan_mode", snapshot.planMode)
            temperature?.let { put("temperature", it) }
        })
        log.append("request/context", buildJsonObject {
            put("step", step)
            put("model", snapshot.model)
            put("message_count", logMessages.size)
            put("context_chars", contextChars)
            put("tool_count", tools.size)
            put("tool_names", toolNames)
        })

        var failureContextLogged = false
        val executor = AgentRequestExecutor(
            maxAttempts = (maxAttemptsOverride ?: snapshot.modelAttempts).coerceIn(1, 5),
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
                        previewOwner?.takeIf { previewGuard() }?.let(streamingPreviewStore::begin)
                    }
                    is AgentRequestEvent.AttemptFailed -> {
                        AppLog.warn(
                            "LocalModelRequest",
                            "主智能体模型请求失败 model=${snapshot.model} step=$step attempt=${event.attempt} " +
                                "retryable=${event.retryable} detail=${event.reason.take(800)}",
                        )
                        if (!failureContextLogged) {
                            runCatching {
                                log.append("request/context-full", buildJsonObject {
                                    put("step", step)
                                    put("model", snapshot.model)
                                    put("messages", JsonArray(logMessages))
                                    put("tools", tools)
                                })
                            }
                            failureContextLogged = true
                        }
                        log.append("request/error", buildJsonObject {
                            put("step", step)
                            put("attempt", event.attempt)
                            put("retryable", event.retryable)
                            put("will_retry", event.willRetry)
                            put("detail", event.reason.take(2_000))
                        })
                        log.append("assistant/attempt", buildJsonObject {
                            put("step", step)
                            put("attempt", event.attempt)
                            put("status", "failed")
                            put("retryable", event.retryable)
                            put("will_retry", event.willRetry)
                            put("detail", event.reason.take(2_000))
                        })
                    }
                    is AgentRequestEvent.RetryScheduled -> {
                        log.append("llm/retry", buildJsonObject {
                            put("step", step)
                            put("attempt", event.attempt)
                            put("next_attempt", event.nextAttempt)
                            put("delay_ms", event.delayMillis)
                        })
                    }
                    is AgentRequestEvent.AttemptCancelled -> {
                        log.append("assistant/attempt", buildJsonObject {
                            put("step", step)
                            put("attempt", event.attempt)
                            put("status", "cancelled")
                            put("will_retry", false)
                            event.reason?.let { put("detail", it.take(2_000)) }
                        })
                    }
                    is AgentRequestEvent.AttemptSucceeded -> Unit
                }
            },
        )

        var activeMessages = messages
        var overflowRound = 0
        while (true) {
            try {
                return try {
                    executor.execute {
                    val streamPreview = LocalStreamPreview(
                        maxChars = maxStreamPreviewChars,
                        minIntervalMs = streamPreviewIntervalMs,
                        clockMs = { System.nanoTime() / 1_000_000 },
                        publish = { preview ->
                            previewOwner
                                ?.takeIf { previewGuard() }
                                ?.let { streamingPreviewStore.publishAssistant(it, preview) }
                        },
                    )
                    val reasoningPreview = LocalStreamPreview(
                        maxChars = maxStreamPreviewChars,
                        minIntervalMs = streamPreviewIntervalMs,
                        clockMs = { System.nanoTime() / 1_000_000 },
                        publish = { preview ->
                            previewOwner
                                ?.takeIf { previewGuard() }
                                ?.let { streamingPreviewStore.publishReasoning(it, preview) }
                        },
                    )
                    val streamFilter = streamFilterPhrases
                        .takeIf { it.isNotEmpty() }
                        ?.let(::ChatStreamFilter)
                    resourceScheduler.withResource(HarnessResourceKind.MODEL_REQUEST) {
                        val reply = try {
                            modelGateway.completeStreaming(
                                baseUrl = snapshot.baseUrl,
                                model = snapshot.model,
                                messages = activeMessages,
                                tools = tools,
                                temperature = temperature,
                                onDelta = { delta ->
                                    val visible = streamFilter?.append(delta.content)?.text ?: delta.content
                                    streamPreview.append(visible)
                                    reasoningPreview.append(delta.reasoning)
                                },
                            )
                        } catch (error: LocalModelException) {
                            log.append("request/provider-error", buildJsonObject {
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
                        streamFilter?.flush()?.text?.takeIf(String::isNotEmpty)?.let(streamPreview::append)
                        streamPreview.flush()
                        reasoningPreview.flush()
                        reply
                    }
                } finally {
                    previewOwner?.let(streamingPreviewStore::clear)
                }
            } catch (error: Throwable) {
                if (!allowContextOverflowRecovery || !contextWindowExceeded(error)) throw error
                val summaryMode = if (snapshot.usageMode == LocalUsageMode.CHAT) {
                    LocalHistorySummaryMode.CHAT
                } else {
                    LocalHistorySummaryMode.WORK
                }
                val compacted = historyCompactor.compactForOverflow(activeMessages, summaryMode)
                    ?: throw error
                val madeProgress =
                    compacted.estimatedTokensAfter < compacted.estimatedTokensBefore &&
                        compacted.messages != activeMessages
                if (!madeProgress) throw error

                overflowRound += 1
                if (persistOverflowHistory) {
                    (overflowPersister ?: persistOverflowCompaction)(snapshot, summaryMode)
                }
                log.append("request/context-overflow-recovery", buildJsonObject {
                    put("step", step)
                    put("round", overflowRound)
                    put("model", snapshot.model)
                    put("estimated_tokens_before", compacted.estimatedTokensBefore)
                    put("estimated_tokens_after", compacted.estimatedTokensAfter)
                    put("omitted_messages", compacted.omittedMessages)
                })
                activeMessages = compacted.messages
            }
        }
    }
}
