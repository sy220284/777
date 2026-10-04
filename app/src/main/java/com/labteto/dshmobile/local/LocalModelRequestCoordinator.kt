package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentRequestEvent
import com.labteto.dshmobile.harness.agent.AgentRequestEventSink
import com.labteto.dshmobile.harness.agent.AgentRequestExecutor
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.local.model.LocalModelCancellationException
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.modelFailureKind
import com.labteto.dshmobile.local.model.LocalStreamingPreviewStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
    private val promptCacheContinuity: LocalPromptCacheContinuityStore = LocalPromptCacheContinuityStore(),
    private val maxStreamPreviewChars: Int = 4_096,
    private val streamPreviewIntervalMs: Long = 50L,
) {
    private val requestRuntime = LocalAgentModelRequestRuntime(modelGateway, resourceScheduler)

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
        val log = requestLog ?: defaultEventLog()
        val frozenProfile = profile ?: modelGateway.profileForRoute(
            snapshot.modelSelection.activeProfileId,
            snapshot.model,
            snapshot.baseUrl,
        )
        val runSurface = frozenProfile.toRunModelSurface()
        val credentialDiagnostic = modelGateway.credentialDiagnostic(frozenProfile)
        val runtimeCapabilities = runSurface.capabilities
        val routeFingerprint = runSurface.routeFingerprint
        val cachePolicy = runSurface.promptCachePolicy
        val cacheComparisonResponseId = if (runtimeCapabilities.promptCacheDiagnostics) {
            promptCacheBaselines.get(snapshot.sessionId, routeFingerprint)
        } else {
            null
        }
        val promptCacheKey = if (cachePolicy.supportsStableCacheKey) {
            stablePromptCacheKey(snapshot.sessionId, routeFingerprint)
        } else {
            null
        }
        val promptCacheTtl = if (cachePolicy.supportsCacheOptions) "30m" else null
        val operationalLimit = operationalInputLimitTokens(
            frozenProfile.model, frozenProfile.baseUrl, frozenProfile.contextWindowTokensOverride,
        )
        val modelContextWindow = documentedContextWindowTokens(
            frozenProfile.model, frozenProfile.baseUrl, frozenProfile.contextWindowTokensOverride,
        )
        val previousSourcePressure = pressureStore.latestSource(
            snapshot.sessionId,
            snapshot.usageMode,
        )
        val baselinePressure = LocalPromptPressureMeter.measure(
            messages = messages,
            tools = tools,
            operationalLimitTokens = operationalLimit,
            modelContextWindowTokens = modelContextWindow,
        )
        val contextProjection = projectLocalRequestContext(
            usageMode = snapshot.usageMode,
            workProjectionEnabled = executionControl != null,
            messages = messages,
            tools = tools,
            compactor = historyCompactor,
            operationalLimitTokens = operationalLimit,
            measuredPressure = baselinePressure,
            previousSourcePressure = previousSourcePressure,
            structuredWorkState = if (
                executionControl != null && snapshot.usageMode == LocalUsageMode.WORK
            ) {
                structuredWorkState(snapshot, log)
            } else {
                null
            },
            cachePolicy = cachePolicy,
            allowSemanticProjection = step <= 1,
        )
        val requestMessages = contextProjection.messages
        val prefixAssessment = if (cachePolicy.mode != LocalPromptCacheMode.NONE) {
            promptCacheContinuity.assess(
                snapshot.sessionId,
                routeFingerprint,
                requestMessages,
                tools,
            )
        } else {
            null
        }
        val pressure = if (contextProjection.projected) {
            LocalPromptPressureMeter.measure(
                messages = requestMessages,
                tools = tools,
                operationalLimitTokens = operationalLimit,
                modelContextWindowTokens = modelContextWindow,
            )
        } else baselinePressure
        val workContextAssessment = if (
            executionControl != null && snapshot.usageMode == LocalUsageMode.WORK
        ) {
            assessWorkStepContext(
                current = pressure,
                previous = previousSourcePressure,
                targetTokens = workRequestProjectionTargetTokens(operationalLimit, cachePolicy),
                baseTriggerTokens = workRequestProjectionTriggerTokens(operationalLimit, cachePolicy),
                growthCurrent = baselinePressure,
                allowAdaptiveEarlyCompaction = cachePolicy.allowAdaptiveEarlyCompaction,
            )
        } else {
            null
        }
        pressureStore.record(
            sessionId = snapshot.sessionId,
            pressure = pressure,
            workAssessment = workContextAssessment,
            workSourcePressure = if (workContextAssessment != null) baselinePressure else null,
            usageMode = snapshot.usageMode,
            sourcePressure = baselinePressure,
        )
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
        if (contextProjection.projected) {
            log.append("request/history-projection", buildJsonObject {
                put("step", step)
                put("mode", snapshot.usageMode.name.lowercase())
                put("source_message_count", messages.size)
                put("projected_message_count", requestMessages.size)
                put("estimated_tokens_before", contextProjection.estimatedTokensBefore)
                put("estimated_tokens_after", contextProjection.estimatedTokensAfter)
                put("omitted_messages", contextProjection.omittedMessages)
                put("strategy", "active_work_checkpoint_plus_recent_causal_tail")
                contextProjection.preProjectionAssessment?.let { assessment ->
                    put("context_status_before", assessment.status.name.lowercase())
                    put("effective_projection_trigger_tokens", assessment.effectiveProjectionTriggerTokens)
                    put("history_ratio_permille_before", assessment.historyRatioPermille)
                    put("history_growth_tokens_before", assessment.historyGrowthTokens)
                    put("projection_reasons", buildJsonArray {
                        assessment.reasons.forEach { add(JsonPrimitive(it)) }
                    })
                }
            })
        }
        val logMessages = redactModelImages(requestMessages)
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
            put("protocol", runSurface.protocol.name)
            put("route_fingerprint", routeFingerprint)
            put("prompt_cache_mode", cachePolicy.mode.name.lowercase())
            put("prompt_cache_key_enabled", promptCacheKey != null)
            put("prompt_cache_ttl_enabled", promptCacheTtl != null)
            put("cache_preserve_tool_surface", cachePolicy.preserveToolSurface)
            prefixAssessment?.let { cache ->
                put("cache_series_generation", cache.generation)
                put("cache_prefix_continuity", cache.continuity.name.lowercase())
                put("cache_tool_surface_stable", cache.toolSurfaceStable)
                put("cache_message_prefix_stable", cache.messagePrefixStable)
                put("cache_previous_message_count", cache.previousMessageCount)
            }
            frozenProfile.credentialRef?.takeLast(8)?.let { put("credential_ref_tail", it) }
            credentialDiagnostic.clientIdTail?.let { put("client_id_tail", it) }
            credentialDiagnostic.selectedAccountTail?.let { put("selected_account_tail", it) }
            credentialDiagnostic.matchesSelectedAccount?.let { put("matches_selected_account", it) }
            put("credential_binding_valid", credentialDiagnostic.bindingValid)
            credentialDiagnostic.planScopeGranted?.let { put("plan_scope_granted", it) }
            credentialDiagnostic.resourceInvokeGranted?.let { put("resource_invoke_granted", it) }
            put("step", step)
            put("source_message_count", messages.size)
            put("message_count", logMessages.size)
            put("history_projected", contextProjection.projected)
            put("estimated_input_tokens_before_projection", contextProjection.estimatedTokensBefore)
            put("context_chars", contextChars)
            put("estimated_input_tokens", pressure.estimatedInputTokens)
            put("operational_input_limit_tokens", pressure.operationalLimitTokens)
            pressure.modelContextWindowTokens?.let { put("model_context_window_tokens", it) }
            put("system_tokens_estimate", pressure.systemTokens)
            put("history_tokens_estimate", pressure.historyTokens)
            put("current_user_tokens_estimate", pressure.currentUserTokens)
            put("tool_definition_tokens_estimate", pressure.toolDefinitionTokens)
            workContextAssessment?.let { assessment ->
                put("context_efficiency_status", assessment.status.name.lowercase())
                put("history_ratio_permille", assessment.historyRatioPermille)
                put("tool_ratio_permille", assessment.toolRatioPermille)
                put("input_growth_tokens", assessment.inputGrowthTokens)
                put("history_growth_tokens", assessment.historyGrowthTokens)
                put("effective_projection_trigger_tokens", assessment.effectiveProjectionTriggerTokens)
                put("context_efficiency_reasons", buildJsonArray {
                    assessment.reasons.forEach { add(JsonPrimitive(it)) }
                })
            }
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

        var failureContextDiagnosticLogged = false
        var lastProviderError: LocalModelException? = null
        var attemptStartedNanos = System.nanoTime()
        val executor = AgentRequestExecutor(
            maxAttempts = (maxAttemptsOverride ?: snapshot.modelAttempts).coerceIn(1, 5),
            retryable = { error ->
                (error as? LocalModelException)?.let { lastProviderError = it }
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
                        val localPreflight = providerError?.code in setOf(
                            "WORK_BUDGET_EXHAUSTED",
                            "MODEL_CONTEXT_BUDGET_EXCEEDED",
                            "MODEL_ROUTE_CIRCUIT_OPEN",
                            "MODEL_ROUTE_CIRCUIT_COOLDOWN",
                        )
                        AppLog.warn(
                            "LocalModelRequest",
                            buildString {
                                append(if (localPreflight) "模型请求本地拒绝 " else "模型请求失败 ")
                                append("model=${snapshot.model} step=$step attempt=${event.attempt} ")
                                append("duration_ms=$durationMs session_id=${snapshot.sessionId} ")
                                providerError?.code?.let { append("code=$it ") }
                                providerError?.let {
                                    append("failure_kind=${modelFailureKind(it)} admission_state=${it.admissionState.name.lowercase()} ")
                                }
                                append("origin=${if (localPreflight) "local_preflight" else "provider_or_transport"} ")
                                append("retryable=${event.retryable} profile_id=${frozenProfile.id} ")
                                append("auth_kind=${frozenProfile.authKind.name} protocol=${runSurface.protocol.name} ")
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
                        if (!failureContextDiagnosticLogged) {
                            runCatching {
                                log.append("request/context-diagnostic", buildJsonObject {
                                    put("step", step)
                                    put("model", frozenProfile.model)
                                    put("profile_id", frozenProfile.id)
                                    put("provider", frozenProfile.provider)
                                    put("auth_kind", frozenProfile.authKind.name)
                                    put("protocol", runSurface.protocol.name)
                                    put("route_fingerprint", routeFingerprint)
                                    put("message_count", logMessages.size)
                                    put("context_chars", contextChars)
                                    put("estimated_input_tokens", pressure.estimatedInputTokens)
                                    put("operational_input_limit_tokens", pressure.operationalLimitTokens)
                                    put("tool_count", tools.size)
                                    put("tool_names", toolNames)
                                })
                            }
                            failureContextDiagnosticLogged = true
                        }
                        log.append("request/error", buildJsonObject {
                            put("duration_ms", durationMs)
                            put("session_id", snapshot.sessionId)
                            put("step", step)
                            put("attempt", event.attempt)
                            put("origin", if (localPreflight) "local_preflight" else "provider_or_transport")
                            providerError?.let { error ->
                                put("code", error.code)
                                put("failure_kind", modelFailureKind(error))
                                put("admission_state", error.admissionState.name.lowercase())
                                put("continuation_eligible", error.continuationEligible)
                                error.status?.let { put("status", it) }
                                error.requestId?.let { put("request_id", it) }
                                error.providerCode?.let { put("provider_code", it) }
                                error.providerParam?.let { put("provider_param", it) }
                            }
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

        var activeMessages = requestMessages
        var overflowRound = 0
        while (true) {
            val activePrefixAssessment = if (cachePolicy.mode != LocalPromptCacheMode.NONE) {
                promptCacheContinuity.assess(
                    snapshot.sessionId,
                    routeFingerprint,
                    activeMessages,
                    tools,
                )
            } else {
                null
            }
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
                        pressureStore.record(
                            snapshot.sessionId,
                            activePressure,
                            usageMode = snapshot.usageMode,
                        )
                        executeWithModelAdmission(
                            control = executionControl,
                            routeFingerprint = routeFingerprint,
                            model = frozenProfile.model,
                            baseUrl = frozenProfile.baseUrl,
                            contextWindowTokensOverride = frozenProfile.contextWindowTokensOverride,
                            messages = activeMessages,
                            tools = tools,
                        ) {
                            try {
                                requestRuntime.complete(
                                    surface = runSurface,
                                    messages = activeMessages,
                                    tools = tools,
                                    streaming = true,
                                    temperature = temperature,
                                    promptCacheComparisonResponseId = cacheComparisonResponseId,
                                    promptCacheKey = promptCacheKey,
                                    promptCacheTtl = promptCacheTtl,
                                    onDelta = { delta ->
                                        val visible = streamFilter?.append(delta.content)?.text ?: delta.content
                                        streamPreview.append(visible)
                                    },
                                )
                                } catch (cancelled: CancellationException) {
                                    val admission =
                                        (cancelled as? LocalModelCancellationException)?.admissionState
                                    log.append("request/cancelled", buildJsonObject {
                                        put("step", step)
                                        admission?.let {
                                            put("admission_state", it.name.lowercase())
                                            put(
                                                "budget_settlement",
                                                if (it == com.labteto.dshmobile.local.model.LocalModelAdmissionState.NOT_SENT) {
                                                    "released"
                                                } else {
                                                    "uncertain_exposure"
                                                },
                                            )
                                        }
                                    })
                                    throw cancelled
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
                                        put("failure_kind", modelFailureKind(error))
                                        put("admission_state", error.admissionState.name.lowercase())
                                        put("continuation_eligible", error.continuationEligible)
                                        error.cause?.message?.takeIf(String::isNotBlank)?.let {
                                            put("cause_detail", it.take(800))
                                        }
                                    })
                                    throw error
                                }.also { reply ->
                                streamFilter?.flush()?.text?.takeIf(String::isNotEmpty)?.let(streamPreview::append)
                                streamPreview.flush()
                                activePrefixAssessment?.let { cache ->
                                    promptCacheContinuity.recordSuccess(
                                        snapshot.sessionId,
                                        routeFingerprint,
                                        activeMessages,
                                        tools,
                                        cache.generation,
                                    )
                                }
                                if (reply.usage.reported) {
                                    pressureStore.recordReportedUsage(snapshot.sessionId, reply.usage.promptTokens)
                                }
                                log.append("request/completed", buildJsonObject {
                                    put("step", step)
                                    put("request_id", reply.requestId)
                                    put("reported", reply.usage.reported)
                                    put("prompt_tokens", reply.usage.promptTokens)
                                    put("cache_hit_tokens", reply.usage.cacheHitTokens)
                                    put("cache_miss_tokens", reply.usage.cacheMissTokens)
                                    put("cache_write_tokens", reply.usage.cacheWriteTokens)
                                    put("completion_tokens", reply.usage.completionTokens)
                                    put("reasoning_tokens", reply.usage.reasoningTokens)
                                    put("total_tokens", reply.usage.totalTokens)
                                    val route = reply.routeIdentity
                                    route?.profileId?.let { put("profile_id", it) }
                                    route?.provider?.takeIf(String::isNotBlank)?.let { put("provider", it) }
                                    route?.authKind?.takeIf(String::isNotBlank)?.let { put("auth_kind", it) }
                                    route?.protocol?.takeIf(String::isNotBlank)?.let { put("protocol", it) }
                                    put("route_fingerprint", routeFingerprint)
                                    activePrefixAssessment?.let { cache ->
                                        put("cache_series_generation_final", cache.generation)
                                        put("cache_prefix_continuity_final", cache.continuity.name.lowercase())
                                        put("cache_tool_surface_stable_final", cache.toolSurfaceStable)
                                        put("cache_message_prefix_stable_final", cache.messagePrefixStable)
                                    }
                                    route?.fingerprint?.takeIf(String::isNotBlank)?.let {
                                        put("reply_route_fingerprint", it)
                                    }
                                })
                                if (runtimeCapabilities.promptCacheDiagnostics && reply.requestId.isNotBlank()) {
                                    promptCacheBaselines.put(snapshot.sessionId, routeFingerprint, reply.requestId)
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
                val summaryMode = snapshot.usageMode.historySummaryMode()
                val compacted = historyCompactor.compactForOverflow(
                    history = activeMessages,
                    summaryMode = summaryMode,
                    structuredWorkState = if (summaryMode == LocalHistorySummaryMode.WORK) {
                        structuredWorkState(snapshot, log)
                    } else {
                        null
                    },
                ) ?: throw error
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
    private fun stablePromptCacheKey(sessionId: String, routeFingerprint: String): String {
        val raw = sessionId + "\u0000" + routeFingerprint
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(64)
    }

}
