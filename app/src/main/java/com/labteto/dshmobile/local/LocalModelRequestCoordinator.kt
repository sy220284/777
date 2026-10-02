package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentRequestEvent
import com.labteto.dshmobile.harness.agent.AgentRequestEventSink
import com.labteto.dshmobile.harness.agent.AgentRequestExecutor
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalStreamingPreviewStore
import com.labteto.dshmobile.local.model.resolveLocalModelProtocol
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
    private val pressureStore: LocalRequestPressureStore = LocalRequestPressureStore(),
    private val promptCacheBaselines: LocalPromptCacheBaselineStore = LocalPromptCacheBaselineStore(),
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
        profile: LocalModelProfile? = null,
        previewGuard: () -> Boolean = { true },
        overflowPersister: ((LocalHarnessState, LocalHistorySummaryMode) -> Unit)? = null,
        executionControl: LocalWorkExecutionControl? = null,
    ): LocalModelReply {
        val tools = toolsOverride ?: toolSchemas(localAgentRunPolicy(snapshot.usageMode))
        val frozenProfile = profile ?: modelGateway.profileForRoute(
            snapshot.modelSelection.activeProfileId,
            snapshot.model,
            snapshot.baseUrl,
        )
        val credentialDiagnostic = modelGateway.credentialDiagnostic(frozenProfile)
        val runtimeCapabilities = LocalModelPresets.runtimeCapabilitiesFor(
            model = frozenProfile.model,
            baseUrl = frozenProfile.baseUrl,
            protocol = resolveLocalModelProtocol(
                authKind = frozenProfile.authKind,
                profile = frozenProfile,
                model = frozenProfile.model,
                baseUrl = frozenProfile.baseUrl,
            ),
            authKind = frozenProfile.authKind,
        )
        val cacheComparisonResponseId = if (runtimeCapabilities.promptCacheDiagnostics) {
            promptCacheBaselines.get(snapshot.sessionId, frozenProfile.id)
        } else {
            null
        }
        val operationalLimit = operationalInputLimitTokens(frozenProfile.model, frozenProfile.baseUrl)
        val pressure = LocalPromptPressureMeter.measure(
            messages = messages,
            tools = tools,
            operationalLimitTokens = operationalLimit,
            modelContextWindowTokens = documentedContextWindowTokens(frozenProfile.model, frozenProfile.baseUrl),
        )
        pressureStore.record(snapshot.sessionId, pressure)
        val contextWindow = pressureStore.window(snapshot.sessionId)
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
            put("profile_id", frozenProfile.id)
            put("provider", frozenProfile.provider)
            put("auth_kind", frozenProfile.authKind.name)
            put("protocol", frozenProfile.protocol.name)
            frozenProfile.credentialRef?.takeLast(8)?.let { put("credential_ref_tail", it) }
            credentialDiagnostic.clientIdTail?.let { put("client_id_tail", it) }
            credentialDiagnostic.selectedAccountTail?.let { put("selected_account_tail", it) }
            credentialDiagnostic.matchesSelectedAccount?.let { put("matches_selected_account", it) }
            put("credential_binding_valid", credentialDiagnostic.bindingValid)
            credentialDiagnostic.planScopeGranted?.let { put("plan_scope_granted", it) }
            credentialDiagnostic.resourceInvokeGranted?.let { put("resource_invoke_granted", it) }
            put("step", step)
            put("message_count", logMessages.size)
            put("context_chars", contextChars)
            put("estimated_input_tokens", pressure.estimatedInputTokens)
            put("operational_input_limit_tokens", pressure.operationalLimitTokens)
            pressure.modelContextWindowTokens?.let { put("model_context_window_tokens", it) }
            put("system_tokens_estimate", pressure.systemTokens)
            put("history_tokens_estimate", pressure.historyTokens)
            put("current_user_tokens_estimate", pressure.currentUserTokens)
            put("tool_definition_tokens_estimate", pressure.toolDefinitionTokens)
            contextWindow?.let { window ->
                put("context_generation", window.generation)
                put("context_prefill_tokens", window.prefillTokens)
                put("context_prefill_source", window.prefillSource)
            }
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
            put("estimated_input_tokens", pressure.estimatedInputTokens)
            put("operational_input_limit_tokens", pressure.operationalLimitTokens)
            put("tool_count", tools.size)
            put("tool_names", toolNames)
        })

        var failureContextLogged = false
        var lastProviderError: LocalModelException? = null
        var attemptStartedNanos = System.nanoTime()
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
                        attemptStartedNanos = System.nanoTime()
                        lastProviderError = null
                        previewOwner?.takeIf { previewGuard() }?.let(streamingPreviewStore::begin)
                    }
                    is AgentRequestEvent.AttemptFailed -> {
                        if (event.willRetry) {
                            previewOwner
                                ?.takeIf { previewGuard() }
                                ?.let(streamingPreviewStore::begin)
                        }
                        val durationMs = (System.nanoTime() - attemptStartedNanos) / 1_000_000
                        val providerError = lastProviderError
                        AppLog.warn(
                            "LocalModelRequest",
                            buildString {
                                append("模型请求失败 model=${snapshot.model} step=$step attempt=${event.attempt} ")
                                append("duration_ms=$durationMs session_id=${snapshot.sessionId} ")
                                providerError?.code?.let { append("code=$it ") }
                                providerError?.cause?.let { append("cause_type=${it::class.java.simpleName} ") }
                                append("retryable=${event.retryable} profile_id=${frozenProfile.id} ")
                                append("auth_kind=${frozenProfile.authKind.name} protocol=${frozenProfile.protocol.name} ")
                                credentialDiagnostic.credentialRefTail?.let { append("credential_ref_tail=$it ") }
                                credentialDiagnostic.clientIdTail?.let { append("client_id_tail=$it ") }
                                credentialDiagnostic.selectedAccountTail?.let { append("selected_account_tail=$it ") }
                                credentialDiagnostic.matchesSelectedAccount?.let { append("selected_match=$it ") }
                                append("binding_valid=${credentialDiagnostic.bindingValid} ")
                                providerError?.status?.let { append("status=$it ") }
                                providerError?.requestId?.let { append("request_id=$it ") }
                                providerError?.providerCode?.let { append("provider_code=$it ") }
                                append("detail=${event.reason.take(800)}")
                            },
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
                            put("duration_ms", durationMs)
                            put("session_id", snapshot.sessionId)
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
                        val streamFilter = streamFilterPhrases
                            .takeIf { it.isNotEmpty() }
                            ?.let(::ChatStreamFilter)
                        val activePressure = LocalPromptPressureMeter.measure(
                            messages = activeMessages,
                            tools = tools,
                            operationalLimitTokens = operationalLimit,
                            modelContextWindowTokens = pressure.modelContextWindowTokens,
                        )
                        pressureStore.record(snapshot.sessionId, activePressure)
                        executeWithModelAdmission(
                            control = executionControl,
                            profileId = frozenProfile.id,
                            model = frozenProfile.model,
                            baseUrl = frozenProfile.baseUrl,
                            messages = activeMessages,
                            tools = tools,
                        ) {
                            resourceScheduler.withResource(HarnessResourceKind.MODEL_REQUEST) {
                                try {
                                    modelGateway.completeStreaming(
                                        baseUrl = snapshot.baseUrl,
                                        model = snapshot.model,
                                        messages = activeMessages,
                                        tools = tools,
                                        temperature = temperature,
                                        profile = frozenProfile,
                                        promptCacheComparisonResponseId = cacheComparisonResponseId,
                                        onDelta = { delta ->
                                            val visible = streamFilter?.append(delta.content)?.text ?: delta.content
                                            streamPreview.append(visible)
                                        },
                                    )
                                } catch (error: LocalModelException) {
                                    lastProviderError = error
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
                            }.also { reply ->
                                streamFilter?.flush()?.text?.takeIf(String::isNotEmpty)?.let(streamPreview::append)
                                streamPreview.flush()
                                if (reply.usage.reported) {
                                    pressureStore.recordReportedUsage(snapshot.sessionId, reply.usage.promptTokens)
                                }
                                if (runtimeCapabilities.promptCacheDiagnostics && reply.requestId.isNotBlank()) {
                                    promptCacheBaselines.put(snapshot.sessionId, frozenProfile.id, reply.requestId)
                                    reply.promptCacheDiagnostic?.let { diagnostic ->
                                        log.append("request/cache-diagnostic", buildJsonObject {
                                            put("step", step)
                                            put("type", diagnostic.type)
                                            diagnostic.reason?.let { put("reason", it) }
                                            diagnostic.comparisonReusableTokens?.let { put("comparison_reusable_tokens", it) }
                                            diagnostic.cacheMissedTokens?.let { put("cache_missed_tokens", it) }
                                            cacheComparisonResponseId?.let { put("comparison_response_id", it) }
                                            put("response_id", reply.requestId)
                                        })
                                    }
                                }
                            }
                        }
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
