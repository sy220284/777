package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentRequestEvent
import com.labteto.dshmobile.harness.agent.AgentRequestEventSink
import com.labteto.dshmobile.harness.agent.AgentRequestExecutor
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
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
    private val modelClient: DeepSeekClient,
    private val resourceScheduler: HarnessResourceScheduler,
    private val historyCompactor: LocalHistoryCompactor,
    private val toolSchemas: (LocalAgentRunPolicy) -> JsonArray,
    private val defaultEventLog: () -> LocalSessionEventLog,
    private val resetPreview: () -> Unit,
    private val publishPreview: (String) -> Unit,
    private val persistOverflowCompaction: (LocalHarnessState, LocalHistorySummaryMode) -> Unit,
    private val maxStreamPreviewChars: Int = 4_096,
    private val streamPreviewIntervalMs: Long = 50L,
) {
    suspend fun complete(
        key: String,
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
    ): LocalModelReply {
        val tools = toolsOverride ?: toolSchemas(localAgentRunPolicy(snapshot.usageMode))
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
            eventSink = AgentRequestEventSink { event ->
                when (event) {
                    is AgentRequestEvent.AttemptStarted -> {
                        if (publishPreviewEnabled) resetPreview()
                    }
                    is AgentRequestEvent.AttemptFailed -> {
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

        return try {
            executor.execute {
                val streamPreview = LocalStreamPreview(
                    maxChars = maxStreamPreviewChars,
                    minIntervalMs = streamPreviewIntervalMs,
                    clockMs = { System.nanoTime() / 1_000_000 },
                    publish = { preview ->
                        if (publishPreviewEnabled) publishPreview(preview)
                    },
                )
                val streamFilter = streamFilterPhrases
                    .takeIf { it.isNotEmpty() }
                    ?.let(::ChatStreamFilter)
                resourceScheduler.withResource(HarnessResourceKind.MODEL_REQUEST) {
                    val reply = modelClient.completeStreaming(
                        apiKey = key,
                        baseUrl = snapshot.baseUrl,
                        model = snapshot.model,
                        messages = messages,
                        tools = tools,
                        onDelta = { delta ->
                            val visible = streamFilter?.append(delta.content)?.text ?: delta.content
                            streamPreview.append(visible)
                        },
                    )
                    streamFilter?.flush()?.text?.takeIf(String::isNotEmpty)?.let(streamPreview::append)
                    streamPreview.flush()
                    reply
                }
            }
        } catch (error: Throwable) {
            if (!allowContextOverflowRecovery || !contextWindowExceeded(error)) throw error
            val summaryMode = if (snapshot.usageMode == LocalUsageMode.CHAT) {
                LocalHistorySummaryMode.CHAT
            } else {
                LocalHistorySummaryMode.WORK
            }
            val compacted = historyCompactor.compactForOverflow(messages, summaryMode)
                ?: throw error
            if (persistOverflowHistory) {
                persistOverflowCompaction(snapshot, summaryMode)
            }
            log.append("request/context-overflow-recovery", buildJsonObject {
                put("step", step)
                put("model", snapshot.model)
                put("estimated_tokens_before", compacted.estimatedTokensBefore)
                put("estimated_tokens_after", compacted.estimatedTokensAfter)
                put("omitted_messages", compacted.omittedMessages)
            })
            complete(
                key = key,
                snapshot = snapshot,
                messages = compacted.messages,
                step = step,
                toolsOverride = tools,
                publishPreviewEnabled = publishPreviewEnabled,
                maxAttemptsOverride = maxAttemptsOverride,
                allowContextOverflowRecovery = false,
                persistOverflowHistory = false,
                streamFilterPhrases = streamFilterPhrases,
                requestLog = log,
            )
        }
    }
}
