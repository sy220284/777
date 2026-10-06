package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentRequestEvent
import com.labteto.dshmobile.harness.agent.AgentRequestEventSink
import com.labteto.dshmobile.local.agent.LocalAgentModelStepRecovery
import com.labteto.dshmobile.local.agent.LocalAgentModelStepRecoveryPolicy
import com.labteto.dshmobile.local.agent.LocalAgentModelStepRuntime
import com.labteto.dshmobile.local.context.LocalRequestContextAssessmentInput
import com.labteto.dshmobile.local.context.LocalRequestContextPolicy
import com.labteto.dshmobile.local.context.LocalRequestContextPolicyInput
import com.labteto.dshmobile.local.context.LocalRequestContextProjection
import com.labteto.dshmobile.local.context.historySummaryMode
import com.labteto.dshmobile.local.context.projectLocalRequestContext
import com.labteto.dshmobile.local.model.LocalStreamPhraseFilter
import com.labteto.dshmobile.local.model.LocalAgentModelRequestRuntime
import com.labteto.dshmobile.local.model.LocalModelAdmissionPort
import com.labteto.dshmobile.local.model.LocalHistoryCompactor
import com.labteto.dshmobile.local.model.LocalForegroundHistoryCompactionRuntime
import com.labteto.dshmobile.local.model.LocalHistorySummaryMode
import com.labteto.dshmobile.local.model.LocalModelCancellationException
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.LocalPromptCacheBaselineStore
import com.labteto.dshmobile.local.model.LocalPromptCacheContinuityStore
import com.labteto.dshmobile.local.model.LocalPromptCacheMode
import com.labteto.dshmobile.local.model.LocalPromptPressureMeter
import com.labteto.dshmobile.local.model.LocalStreamPreview
import com.labteto.dshmobile.local.model.modelFailureKind
import com.labteto.dshmobile.local.model.redactModelImages
import com.labteto.dshmobile.local.model.routeFingerprint
import com.labteto.dshmobile.local.model.toRunModelSurface
import com.labteto.dshmobile.local.model.buildLocalRequestEvidence
import com.labteto.dshmobile.local.model.stableJsonSha256
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.observability.AppLog
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
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
 * recovery. Callers supply the bounded product snapshot and durable compaction callback.
 */
@Singleton
internal class LocalModelRequestCoordinator @Inject constructor(
    private val modelGateway: LocalModelGateway,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val foregroundCompaction: LocalForegroundHistoryCompactionRuntime,
) {
    private val resourceScheduler
        get() = runtimeStateStore.resourceScheduler
    private val streamingPreviewStore
        get() = runtimeStateStore.streamingPreviewStore
    private val pressureStore
        get() = runtimeStateStore.requestPressureStore
    private val historyCompactor = LocalHistoryCompactor()
    private val promptCacheBaselines = LocalPromptCacheBaselineStore()
    private val promptCacheContinuity = LocalPromptCacheContinuityStore()
    private val maxStreamPreviewChars: Int = 4_096
    private val streamPreviewIntervalMs: Long = 50L
    private val requestRuntime = LocalAgentModelRequestRuntime(modelGateway, resourceScheduler)
    private val modelStepRuntime = LocalAgentModelStepRuntime()
    private val evidenceLock = Any()
    private val toolSurfaceEvidence = mutableMapOf<String, RequestEvidenceRef>()
    private val contextSurfaceEvidence = mutableMapOf<String, RequestEvidenceRef>()

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
        contextPolicy: LocalRequestContextPolicy? = null,
        admission: LocalModelAdmissionPort? = null,
    ): LocalModelReply {
        val tools = toolsOverride ?: JsonArray(emptyList())
        val log = requestLog ?: sessionStorage.eventLogs.get(snapshot.sessionId)
        val frozenProfile = profile ?: modelGateway.profileForRoute(
            snapshot.modelState.modelSelection.activeProfileId,
            snapshot.modelState.model,
            snapshot.modelState.baseUrl,
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
            workProjectionEnabled = contextPolicy != null,
            messages = messages,
            measuredPressure = baselinePressure,
            workProjection = contextPolicy?.let { policy ->
                {
                    policy.project(
                        LocalRequestContextPolicyInput(
                            messages = messages,
                            tools = tools,
                            operationalLimitTokens = operationalLimit,
                            measuredPressure = baselinePressure,
                            previousPressure = previousSourcePressure,
                            cachePolicy = cachePolicy,
                            allowSemanticProjection = step <= 1,
                        ),
                    )
                }
            },
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
        val contextAssessment = if (
            contextPolicy != null && snapshot.usageMode == LocalUsageMode.WORK
        ) {
            contextPolicy.assess(
                LocalRequestContextAssessmentInput(
                    current = pressure,
                    previous = previousSourcePressure,
                    operationalLimitTokens = operationalLimit,
                    cachePolicy = cachePolicy,
                    growthCurrent = baselinePressure,
                ),
            )
        } else {
            null
        }
        pressureStore.record(
            sessionId = snapshot.sessionId,
            pressure = pressure,
            contextAssessment = contextAssessment,
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
                    put("context_status_before", assessment.status)
                    put("effective_projection_trigger_tokens", assessment.effectiveProjectionTriggerTokens)
                    put("history_ratio_permille_before", assessment.historyRatioPermille)
                    put("history_growth_tokens_before", assessment.historyGrowthTokens)
                    put("projection_reasons", buildJsonArray {
                        assessment.reasons.forEach { add(JsonPrimitive(it)) }
                    })
                }
            })
        }
        val requestUid = UUID.randomUUID().toString()
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
        val requestEvidence = buildLocalRequestEvidence(requestMessages, tools)
        val evidenceKey = snapshot.sessionId + "\u0000" + routeFingerprint
        val evidenceLogSequence = log.latestSequence()
        val toolSurfaceSeq = ensureRequestEvidenceSurface(
            cache = toolSurfaceEvidence,
            key = evidenceKey,
            digest = requestEvidence.toolSchemaDigest,
            currentLogSequence = evidenceLogSequence,
        ) {
            log.append("request/tool-surface", buildJsonObject {
                put("version", REQUEST_EVIDENCE_VERSION)
                put("digest", requestEvidence.toolSchemaDigest)
                put("schemas", tools)
            }).sequence
        }
        val contextSurfaceSeq = ensureRequestEvidenceSurface(
            cache = contextSurfaceEvidence,
            key = evidenceKey,
            digest = requestEvidence.contextDigest,
            currentLogSequence = log.latestSequence(),
        ) {
            log.append("request/context-surface", buildJsonObject {
                put("version", REQUEST_EVIDENCE_VERSION)
                put("digest", requestEvidence.contextDigest)
                put("messages", requestEvidence.contextMessages)
            }).sequence
        }
        val requestEnvelopeFingerprint = stableJsonSha256(buildJsonArray {
            add(JsonPrimitive(routeFingerprint))
            add(JsonPrimitive(requestEvidence.toolSchemaDigest))
            add(JsonPrimitive(requestEvidence.contextDigest))
        })
        val requestHeader = log.append("request/header", buildJsonObject {
            put("version", REQUEST_EVIDENCE_VERSION)
            put("request_uid", requestUid)
            put("model", snapshot.modelState.model)
            put("base_url", snapshot.modelState.baseUrl)
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
            contextAssessment?.let { assessment ->
                put("context_efficiency_status", assessment.status)
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
            put("message_digest", requestEvidence.messageDigest)
            put("tool_schema_digest", requestEvidence.toolSchemaDigest)
            put("context_surface_digest", requestEvidence.contextDigest)
            put("tool_surface_seq", toolSurfaceSeq)
            put("context_surface_seq", contextSurfaceSeq)
            put("request_envelope_fingerprint", requestEnvelopeFingerprint)
            put("plan_mode", snapshot.work.planMode)
            temperature?.let { put("temperature", it) }
        })
        log.append("request/context", buildJsonObject {
            put("version", REQUEST_EVIDENCE_VERSION)
            put("request_uid", requestUid)
            put("header_seq", requestHeader.sequence)
            put("request_envelope_fingerprint", requestEnvelopeFingerprint)
            put("step", step)
            put("model", snapshot.modelState.model)
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
        val executor = modelStepRuntime.requestExecutor(
            maxAttempts = (maxAttemptsOverride ?: snapshot.modelState.modelAttempts).coerceIn(1, 5),
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
                                append("model=${snapshot.modelState.model} step=$step attempt=${event.attempt} ")
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
                            put("request_uid", requestUid)
                            put("request_envelope_fingerprint", requestEnvelopeFingerprint)
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
                            put("request_uid", requestUid)
                            put("request_envelope_fingerprint", requestEnvelopeFingerprint)
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
                            put("request_uid", requestUid)
                            put("step", step)
                            put("attempt", event.attempt)
                            put("next_attempt", event.nextAttempt)
                            put("delay_ms", event.delayMillis)
                        })
                    }
                    is AgentRequestEvent.AttemptCancelled -> {
                        log.append("assistant/attempt", buildJsonObject {
                            put("request_uid", requestUid)
                            put("request_envelope_fingerprint", requestEnvelopeFingerprint)
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

        var overflowRound = 0
        return modelStepRuntime.recover(
            initialMessages = requestMessages,
            recoveryPolicy = LocalAgentModelStepRecoveryPolicy { error, activeMessages, _ ->
                if (!allowContextOverflowRecovery || !contextWindowExceeded(error)) {
                    null
                } else {
                    val summaryMode = snapshot.usageMode.historySummaryMode()
                    val compacted = historyCompactor.compactForOverflow(
                        history = activeMessages,
                        summaryMode = summaryMode,
                    )
                    val madeProgress = compacted != null &&
                        compacted.estimatedTokensAfter < compacted.estimatedTokensBefore &&
                        compacted.messages != activeMessages
                    if (!madeProgress) {
                        null
                    } else {
                        val recovered = checkNotNull(compacted)
                        overflowRound += 1
                        if (persistOverflowHistory) {
                            if (overflowPersister != null) {
                                overflowPersister(snapshot, summaryMode)
                            } else if (!snapshot.chat.groupChat.enabled) {
                                foregroundCompaction.persistOverflowCompaction(snapshot.sessionId, summaryMode)
                            }
                        }
                        log.append("request/context-overflow-recovery", buildJsonObject {
                            put("request_uid", requestUid)
                            put("step", step)
                            put("round", overflowRound)
                            put("model", snapshot.modelState.model)
                            put("estimated_tokens_before", recovered.estimatedTokensBefore)
                            put("estimated_tokens_after", recovered.estimatedTokensAfter)
                            put("omitted_messages", recovered.omittedMessages)
                        })
                        LocalAgentModelStepRecovery(
                            messages = recovered.messages,
                            reason = "context_overflow",
                        )
                    }
                }
            },
        ) { activeMessages ->
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
                        ?.let(::LocalStreamPhraseFilter)
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
                    val reply = try {
                        requestRuntime.complete(
                                surface = runSurface,
                                messages = activeMessages,
                                tools = tools,
                                streaming = true,
                                temperature = temperature,
                                promptCacheComparisonResponseId = cacheComparisonResponseId,
                                promptCacheKey = promptCacheKey,
                            promptCacheTtl = promptCacheTtl,
                            admission = admission,
                            onDelta = { delta ->
                                    val visible = streamFilter?.append(delta.content)?.text ?: delta.content
                                    streamPreview.append(visible)
                                },
                            )
                        } catch (cancelled: CancellationException) {
                            val admissionState =
                                (cancelled as? LocalModelCancellationException)?.admissionState
                            log.append("request/cancelled", buildJsonObject {
                                put("request_uid", requestUid)
                                put("step", step)
                                admissionState?.let {
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
                                put("request_uid", requestUid)
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
                        }
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
                                put("request_uid", requestUid)
                                put("request_envelope_fingerprint", requestEnvelopeFingerprint)
                                put("header_seq", requestHeader.sequence)
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
                                        put("request_uid", requestUid)
                                        put("step", step)
                                        put("type", diagnostic.type)
                                        diagnostic.reason?.let { put("reason", it) }
                                        diagnostic.comparisonReusableTokens?.let {
                                            put("comparison_reusable_tokens", it)
                                        }
                                        diagnostic.cacheMissedTokens?.let { put("cache_missed_tokens", it) }
                                        cacheComparisonResponseId?.let { put("comparison_response_id", it) }
                                        put("response_id", reply.requestId)
                                    })
                                }
                            }
                            reply
                }
            } finally {
                previewOwner?.let(streamingPreviewStore::clear)
            }
        }
    }

    private fun ensureRequestEvidenceSurface(
        cache: MutableMap<String, RequestEvidenceRef>,
        key: String,
        digest: String,
        currentLogSequence: Long,
        append: () -> Long,
    ): Long = synchronized(evidenceLock) {
        val existing = cache[key]
        if (
            existing?.digest == digest &&
            existing.sequence <= currentLogSequence
        ) {
            existing.sequence
        } else {
            val sequence = append()
            cache[key] = RequestEvidenceRef(digest = digest, sequence = sequence)
            sequence
        }
    }

    private fun stablePromptCacheKey(sessionId: String, routeFingerprint: String): String {
        val raw = sessionId + "\u0000" + routeFingerprint
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(64)
    }

    private data class RequestEvidenceRef(
        val digest: String,
        val sequence: Long,
    )

    private companion object {
        const val REQUEST_EVIDENCE_VERSION = 1
    }
}
