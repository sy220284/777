package com.labteto.dshmobile.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
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
import com.labteto.dshmobile.local.model.DeepSeekChatRequestPolicy
import com.labteto.dshmobile.local.model.LocalAgentModelRequestRuntime
import com.labteto.dshmobile.local.model.LocalModelAdmissionPort
import com.labteto.dshmobile.local.model.LocalHistoryCompactor
import com.labteto.dshmobile.local.model.LocalForegroundHistoryCompactionRuntime
import com.labteto.dshmobile.local.model.LocalHistorySummaryMode
import com.labteto.dshmobile.local.model.LocalModelCancellationException
import com.labteto.dshmobile.local.model.LocalModelAttemptStreamEvidence
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.freezeLocalModelRequestConfiguration
import com.labteto.dshmobile.local.model.LocalModelRequestSelection
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.LocalReasoningModeStore
import com.labteto.dshmobile.local.model.LocalPromptCacheBaselineStore
import com.labteto.dshmobile.local.model.LocalPromptCacheContinuityStore
import com.labteto.dshmobile.local.model.LocalPromptCacheMode
import com.labteto.dshmobile.local.model.LocalPromptPressure
import com.labteto.dshmobile.local.model.LocalPromptPressureMeter
import com.labteto.dshmobile.local.model.LocalRequestReconstruction
import com.labteto.dshmobile.local.model.reconstructLocalModelRequest
import com.labteto.dshmobile.local.model.LocalStreamPreview
import com.labteto.dshmobile.local.session.LocalChatStreamCheckpointWriter
import com.labteto.dshmobile.local.model.modelFailureKind
import com.labteto.dshmobile.local.model.modelTransportFailureKind
import com.labteto.dshmobile.local.model.redactModelImages
import com.labteto.dshmobile.local.model.routeFingerprint
import com.labteto.dshmobile.local.model.LOCAL_NATIVE_TOOL_IMAGE_GENERATION
import com.labteto.dshmobile.local.model.buildLocalRequestEvidence
import com.labteto.dshmobile.local.model.resolveLocalNativeToolNames
import com.labteto.dshmobile.local.model.stableJsonSha256
import com.labteto.dshmobile.local.runtime.CHAT_POST_TURN_MODEL_STEP
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.observability.sanitizeDiagnosticText
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

/** Keep local history/policy failures separate from provider/network errors in exported diagnostics. */
internal fun localModelRequestErrorOrigin(code: String?): String =
    if (code in setOf("WORK_BUDGET_EXHAUSTED", "MODEL_CONTEXT_BUDGET_EXCEEDED", "MODEL_HISTORY_INVALID")) {
        "local_preflight"
    } else {
        "provider_or_transport"
    }

/**
 * Owns foreground model transport semantics: request logging, retry, streaming preview and overflow
 * recovery. Callers supply the bounded product snapshot and durable compaction callback.
 */
@Singleton
internal class LocalModelRequestCoordinator @Inject constructor(
    summaries: com.labteto.dshmobile.local.model.LocalHistorySummaryProvider,
    private val modelGateway: LocalModelGateway,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val foregroundCompaction: LocalForegroundHistoryCompactionRuntime,
    @ApplicationContext context: Context,
) {
    init {
        LocalReasoningModeStore.attach(context)
        com.labteto.dshmobile.local.model.LocalModelPerformanceStore.attach(context)
        com.labteto.dshmobile.local.model.LocalWorkTemperatureStore.attach(context)
    }

    private val networkContext: Context = context
    private val resourceScheduler
        get() = runtimeStateStore.resourceScheduler
    private val streamingPreviewStore
        get() = runtimeStateStore.streamingPreviewStore
    private val pressureStore
        get() = runtimeStateStore.requestPressureStore
    private val historyCompactor = LocalHistoryCompactor(summaries = summaries)
    private val promptCacheBaselines = LocalPromptCacheBaselineStore()
    private val promptCacheContinuity = LocalPromptCacheContinuityStore()
    // Retain a full ordinary reply while limiting memory and Compose parsing cost.
    // Extremely long tool sessions still rely on durable stage messages, not this buffer.
    private val maxStreamPreviewChars: Int = 65_536
    private val streamPreviewIntervalMs: Long = 100L
    private val requestRuntime = LocalAgentModelRequestRuntime(modelGateway, resourceScheduler)
    private val modelStepRuntime = LocalAgentModelStepRuntime()
    // EventLog owns the actual model request evidence. This helper only memoizes
    // immutable evidence references across steps of this coordinator's process lifetime.
    private val evidenceSurfaces = LocalRequestEvidenceSurfaceMemo()

    fun reconstructRequest(
        sessionId: String,
        requestUid: String,
    ): LocalRequestReconstruction? =
        reconstructLocalModelRequest(
            eventLog = sessionStorage.eventLogs.get(sessionId),
            requestUid = requestUid,
        )

    suspend fun complete(
        snapshot: LocalHarnessState,
        messages: List<JsonObject>,
        step: Int,
        options: LocalModelRequestOptions = LocalModelRequestOptions(),
    ): LocalModelReply {
        val toolsOverride = options.toolsOverride
        val publishPreviewEnabled = options.publishPreviewEnabled
        val maxAttemptsOverride = options.maxAttemptsOverride
        val allowContextOverflowRecovery = options.allowContextOverflowRecovery
        val persistOverflowHistory = options.persistOverflowHistory
        val requestLog = options.requestLog
        val temperature = options.temperature
        val profile = options.profile
        val previewGuard = options.previewGuard
        val overflowPersister = options.overflowPersister
        val contextPolicy = options.contextPolicy
        val allowImageGeneration = options.allowImageGeneration
        val admission = options.admission
        val tools = toolsOverride ?: JsonArray(emptyList())
        val log = requestLog ?: sessionStorage.eventLogs.get(snapshot.sessionId)
        val frozen = freezeLocalModelRequestConfiguration(
            selection = LocalModelRequestSelection(
                sessionId = snapshot.sessionId,
                activeProfileId = snapshot.modelState.modelSelection.activeProfileId,
                model = snapshot.modelState.model,
                baseUrl = snapshot.modelState.baseUrl,
                usageMode = snapshot.usageMode,
            ),
            profile = profile,
            tools = tools,
            temperature = temperature,
            modelGateway = modelGateway,
        )
        val frozenProfile = frozen.profile
        val runSurface = frozen.surface
        val reasoningEffort = frozen.reasoningEffort
        val effectiveTemperature = frozen.temperature
        val credentialDiagnostic = frozen.credentialDiagnostic
        val runtimeCapabilities = runSurface.capabilities
        val routeFingerprint = runSurface.routeFingerprint
        val cachePolicy = runSurface.promptCachePolicy
        // Foreground Chat and background state consolidation have distinct prompt series.
        // This changes diagnostics only; actual provider caching and message content stay untouched.
        val cacheDiagnosticSurface = when {
            snapshot.usageMode == LocalUsageMode.CHAT && step >= CHAT_POST_TURN_MODEL_STEP -> "chat_auxiliary_$step"
            snapshot.usageMode == LocalUsageMode.CHAT -> "chat_foreground"
            else -> "work"
        }
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
        val operationalLimit = runSurface.contract.operationalInputLimitTokens
        val modelContextWindow = runSurface.contract.documentedContextWindowTokens
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
        val softProjectionScale = pressureStore.softProjectionScale(
            routeFingerprint = routeFingerprint,
            rawEstimatedInputTokens = baselinePressure.estimatedInputTokens,
            operationalLimitTokens = operationalLimit,
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
                            softProjectionScale = softProjectionScale,
                        ),
                    )
                }
            },
        )
        val requestMessages = contextProjection.messages
        val nativeToolNames = resolveLocalNativeToolNames(runSurface, allowImageGeneration)
        val nativeTools = JsonArray(nativeToolNames.map(::JsonPrimitive))
        val effectiveImageGeneration =
            LOCAL_NATIVE_TOOL_IMAGE_GENERATION in nativeToolNames
        val prefixAssessment = if (cachePolicy.mode != LocalPromptCacheMode.NONE) {
            promptCacheContinuity.assess(
                snapshot.sessionId,
                routeFingerprint,
                requestMessages,
                tools,
                nativeTools,
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
        // Use the same 85% cutoff for the assessment as for the projection itself.
        val softAssessmentPressure: (LocalPromptPressure) -> LocalPromptPressure = { raw ->
            if (softProjectionScale == 1.0) raw
            else pressureStore.forSoftProjection(routeFingerprint, raw)
        }
        val contextAssessment = if (
            contextPolicy != null && snapshot.usageMode == LocalUsageMode.WORK
        ) {
            contextPolicy.assess(
                LocalRequestContextAssessmentInput(
                    current = softAssessmentPressure(pressure),
                    previous = previousSourcePressure?.let(softAssessmentPressure),
                    operationalLimitTokens = operationalLimit,
                    cachePolicy = cachePolicy,
                    growthCurrent = softAssessmentPressure(baselinePressure),
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
        try {
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
        val requestEvidence = buildLocalRequestEvidence(requestMessages, tools, nativeTools)
        val replayEvidence = buildLocalRequestEvidence(logMessages, tools, nativeTools)
        val evidenceKey = snapshot.sessionId + "\u0000" + routeFingerprint
        val evidenceLogSequence = log.latestSequence()
        val messageSurfaceSeq = evidenceSurfaces.ensure(
            kind = LocalRequestSurfaceKind.MESSAGE,
            key = evidenceKey,
            digest = replayEvidence.messageDigest,
            currentLogSequence = evidenceLogSequence,
        ) {
            log.append("request/message-surface", buildJsonObject {
                put("version", REQUEST_EVIDENCE_VERSION)
                put("digest", replayEvidence.messageDigest)
                put("messages", JsonArray(logMessages))
                put(
                    "redacted",
                    replayEvidence.messageDigest != requestEvidence.messageDigest,
                )
            }).sequence
        }
        val toolSurfaceSeq = evidenceSurfaces.ensure(
            kind = LocalRequestSurfaceKind.TOOL,
            key = evidenceKey,
            digest = requestEvidence.resolvedToolSurfaceDigest,
            currentLogSequence = evidenceLogSequence,
        ) {
            log.append("request/tool-surface", buildJsonObject {
                put("version", REQUEST_EVIDENCE_VERSION)
                put("digest", requestEvidence.resolvedToolSurfaceDigest)
                put("schema_digest", requestEvidence.toolSchemaDigest)
                put("schemas", tools)
                put("native_tools", nativeTools)
            }).sequence
        }
        val contextSurfaceSeq = evidenceSurfaces.ensure(
            kind = LocalRequestSurfaceKind.CONTEXT,
            key = evidenceKey,
            digest = replayEvidence.contextDigest,
            currentLogSequence = log.latestSequence(),
        ) {
            log.append("request/context-surface", buildJsonObject {
                put("version", REQUEST_EVIDENCE_VERSION)
                put("digest", replayEvidence.contextDigest)
                put("messages", replayEvidence.contextMessages)
                put(
                    "redacted",
                    replayEvidence.contextDigest != requestEvidence.contextDigest,
                )
            }).sequence
        }
        val requestEnvelopeFingerprint = stableJsonSha256(buildJsonArray {
            add(JsonPrimitive(routeFingerprint))
            add(JsonPrimitive(requestEvidence.messageDigest))
            add(JsonPrimitive(requestEvidence.resolvedToolSurfaceDigest))
            add(JsonPrimitive(requestEvidence.contextDigest))
        })
        val requestHeader = log.append("request/header", buildJsonObject {
            put("version", REQUEST_EVIDENCE_VERSION)
            put("request_uid", requestUid)
            put("model", runSurface.model)
            put("base_url", runSurface.baseUrl)
            put("profile_id", frozenProfile.id)
            put("provider", frozenProfile.provider)
            put("auth_kind", frozenProfile.authKind.name)
            put("protocol", runSurface.protocol.name)
            put("route_fingerprint", routeFingerprint)
            put("model_contract_version", 1)
            put("contract_operational_input_limit", runSurface.contract.operationalInputLimitTokens)
            runSurface.contract.documentedContextWindowTokens?.let { put("contract_documented_context_window", it) }
            put("contract_work_requests_per_slice", runSurface.contract.executionBudget.workRequestsPerSlice)
            put("contract_image_input_status", runSurface.contract.imageInput.status.name.lowercase())
            put("contract_tool_calling_status", runSurface.contract.toolCalling.status.name.lowercase())
            put("soft_projection_scale_permille", (softProjectionScale * 1_000).toInt())
            put("prompt_cache_mode", cachePolicy.mode.name.lowercase())
            put("prompt_cache_key_enabled", promptCacheKey != null)
            put("prompt_cache_ttl_enabled", promptCacheTtl != null)
            put("image_generation_requested", allowImageGeneration)
            put("image_generation_enabled", effectiveImageGeneration)
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
            put("native_tool_count", nativeTools.size)
            put("native_tool_names", nativeTools)
            put("message_digest", requestEvidence.messageDigest)
            put("context_digest", requestEvidence.contextDigest)
            put("tool_schema_digest", requestEvidence.toolSchemaDigest)
            put("resolved_tool_surface_digest", requestEvidence.resolvedToolSurfaceDigest)
            put("message_surface_digest", replayEvidence.messageDigest)
            put("context_surface_digest", replayEvidence.contextDigest)
            put("message_surface_seq", messageSurfaceSeq)
            put("tool_surface_seq", toolSurfaceSeq)
            put("context_surface_seq", contextSurfaceSeq)
            put(
                "message_surface_redacted",
                replayEvidence.messageDigest != requestEvidence.messageDigest,
            )
            put(
                "context_surface_redacted",
                replayEvidence.contextDigest != requestEvidence.contextDigest,
            )
            put("request_envelope_fingerprint", requestEnvelopeFingerprint)
            put("plan_mode", snapshot.work.planMode)
            reasoningEffort?.let { put("reasoning_effort", it) }
            effectiveTemperature?.let { put("temperature", it) }
        })
        log.append("request/context", buildJsonObject {
            put("version", REQUEST_EVIDENCE_VERSION)
            put("request_uid", requestUid)
            put("header_seq", requestHeader.sequence)
            put("request_envelope_fingerprint", requestEnvelopeFingerprint)
            put("step", step)
            put("model", runSurface.model)
            put("message_count", logMessages.size)
            put("context_chars", contextChars)
            put("estimated_input_tokens", pressure.estimatedInputTokens)
            put("operational_input_limit_tokens", pressure.operationalLimitTokens)
            put("tool_count", tools.size)
            put("tool_names", toolNames)
            put("native_tool_count", nativeTools.size)
            put("native_tool_names", nativeTools)
            put("resolved_tool_surface_digest", requestEvidence.resolvedToolSurfaceDigest)
        })

        var failureContextDiagnosticLogged = false
        var lastProviderError: LocalModelException? = null
        val networkPathAtStart = describeModelNetworkPath(networkContext)
        var attemptStartedNanos = System.nanoTime()
        var providerStartedNanos: Long? = null
        val attemptEvidence = LocalModelAttemptStreamEvidence()
        val executor = modelStepRuntime.requestExecutor(
            maxAttempts = (maxAttemptsOverride ?: snapshot.modelState.modelAttempts).coerceIn(1, 5),
            retryable = { error ->
                (error as? LocalModelException)?.let { lastProviderError = it }
                attemptEvidence.retryable(error)
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
                        providerStartedNanos = null
                        lastProviderError = null
                        attemptEvidence.beginAttempt()
                        previewOwner?.takeIf { previewGuard() }?.let { streamingPreviewStore.begin(it, event.attempt) }
                    }
                    is AgentRequestEvent.AttemptFailed -> {
                        val durationMs = (System.nanoTime() - attemptStartedNanos) / 1_000_000
                        // Step time includes scheduling; provider time starts after resource grant.
                        val preProviderMs = providerStartedNanos?.let { (it - attemptStartedNanos).coerceAtLeast(0L) / 1_000_000 }
                        val providerExecutionMs = providerStartedNanos?.let { (System.nanoTime() - it).coerceAtLeast(0L) / 1_000_000 }
                        val providerError = lastProviderError
                        val localPreflight = localModelRequestErrorOrigin(providerError?.code) == "local_preflight"
                        AppLog.warn(
                            "LocalModelRequest",
                            buildString {
                                append(if (localPreflight) "模型请求本地拒绝 " else "模型请求失败 ")
                                append("model=${snapshot.modelState.model} step=$step attempt=${event.attempt} request_uid=$requestUid ")
                                append("duration_ms=$durationMs session_id=${snapshot.sessionId} ")
                                preProviderMs?.let { append("pre_provider_ms=$it ") }
                                providerExecutionMs?.let { append("provider_execution_ms=$it ") }
                                providerError?.code?.let { append("code=$it ") }
                                providerError?.let {
                                    append("failure_kind=${modelFailureKind(it)} admission_state=${it.admissionState.name.lowercase()} ")
                                    modelTransportFailureKind(it)?.let { kind -> append("transport_kind=$kind ") }
                                    append("network_path=${describeModelNetworkPath(networkContext)} ")
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
                            providerError,
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
                            preProviderMs?.let { put("pre_provider_ms", it) }
                            providerExecutionMs?.let { put("provider_execution_ms", it) }
                            put("session_id", snapshot.sessionId)
                            put("step", step)
                            put("attempt", event.attempt)
                            put("origin", if (localPreflight) "local_preflight" else "provider_or_transport")
                            providerError?.let { error ->
                                put("code", error.code)
                                put("failure_kind", modelFailureKind(error))
                                modelTransportFailureKind(error)?.let { put("transport_kind", it) }
                                put("network_path_at_start", networkPathAtStart)
                                put("network_path_at_failure", describeModelNetworkPath(networkContext))
                                put("admission_state", error.admissionState.name.lowercase())
                                put("continuation_eligible", error.continuationEligible)
                                error.status?.let { put("status", it) }
                                error.requestId?.let { put("request_id", it) }
                                error.providerCode?.let { put("provider_code", it) }
                                error.providerParam?.let { put("provider_param", it) }
                                error.cause?.let { cause ->
                                    put("cause_type", cause.javaClass.simpleName)
                                    cause.message?.let { put("cause_detail", sanitizeDiagnosticText(it).take(800)) }
                                }
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
                            put("stream_total_chars", attemptEvidence.emittedChars)
                            if (attemptEvidence.tail.isNotEmpty()) {
                                put("stream_tail", attemptEvidence.tail)
                                put("stream_tail_truncated", attemptEvidence.truncated)
                            }
                            put("retryable", event.retryable)
                            put("will_retry", event.willRetry)
                            put("detail", event.reason.take(2_000))
                        })
                    }
                    is AgentRequestEvent.RetryScheduled -> {
                        previewOwner?.takeIf { previewGuard() }?.let {
                            streamingPreviewStore.retry(it, event.nextAttempt)
                        }
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
                            put("stream_total_chars", attemptEvidence.emittedChars)
                            if (attemptEvidence.tail.isNotEmpty()) {
                                put("stream_tail", attemptEvidence.tail)
                                put("stream_tail_truncated", attemptEvidence.truncated)
                            }
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
                            put("model", runSurface.model)
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
                    nativeTools,
                    diagnosticSurface = cacheDiagnosticSurface,
                )
            } else {
                null
            }
                executor.execute { providerAttempt ->
                    val activeLogMessages = redactModelImages(activeMessages)
                    val activeEvidence = buildLocalRequestEvidence(activeMessages, tools, nativeTools)
                    val activeReplayEvidence = buildLocalRequestEvidence(activeLogMessages, tools, nativeTools)
                    val activeMessageSurfaceSeq = evidenceSurfaces.ensure(
                        kind = LocalRequestSurfaceKind.MESSAGE,
                        key = evidenceKey,
                        digest = activeReplayEvidence.messageDigest,
                        currentLogSequence = log.latestSequence(),
                    ) {
                        log.append("request/message-surface", buildJsonObject {
                            put("version", REQUEST_EVIDENCE_VERSION)
                            put("digest", activeReplayEvidence.messageDigest)
                            put("messages", JsonArray(activeLogMessages))
                            put(
                                "redacted",
                                activeReplayEvidence.messageDigest != activeEvidence.messageDigest,
                            )
                        }).sequence
                    }
                    val activeContextSurfaceSeq = evidenceSurfaces.ensure(
                        kind = LocalRequestSurfaceKind.CONTEXT,
                        key = evidenceKey,
                        digest = activeReplayEvidence.contextDigest,
                        currentLogSequence = log.latestSequence(),
                    ) {
                        log.append("request/context-surface", buildJsonObject {
                            put("version", REQUEST_EVIDENCE_VERSION)
                            put("digest", activeReplayEvidence.contextDigest)
                            put("messages", activeReplayEvidence.contextMessages)
                            put(
                                "redacted",
                                activeReplayEvidence.contextDigest != activeEvidence.contextDigest,
                            )
                        }).sequence
                    }
                    val activePressure = LocalPromptPressureMeter.measure(
                        messages = activeMessages,
                        tools = tools,
                        operationalLimitTokens = operationalLimit,
                        modelContextWindowTokens = pressure.modelContextWindowTokens,
                    )
                    val deepSeekMaxTokens = if (
                        runSurface.protocol == com.labteto.dshmobile.local.model.LocalModelProtocol.CHAT_COMPLETIONS
                    ) {
                        DeepSeekChatRequestPolicy.outputBudget(
                            model = runSurface.model,
                            baseUrl = runSurface.baseUrl,
                            reasoningEffort = reasoningEffort,
                            work = snapshot.usageMode == LocalUsageMode.WORK,
                            estimatedInputTokens = activePressure.estimatedInputTokens,
                            contextWindowTokens = pressure.modelContextWindowTokens,
                        )
                    } else null
                    val providerAttemptFingerprint = stableJsonSha256(buildJsonArray {
                        add(JsonPrimitive(routeFingerprint))
                        add(JsonPrimitive(activeEvidence.messageDigest))
                        add(JsonPrimitive(activeEvidence.resolvedToolSurfaceDigest))
                        add(JsonPrimitive(activeEvidence.contextDigest))
                        add(JsonPrimitive(effectiveTemperature?.toString() ?: "null"))
                        add(JsonPrimitive(reasoningEffort ?: "default"))
                        add(JsonPrimitive(deepSeekMaxTokens?.toString() ?: "default"))
                        add(JsonPrimitive(cacheComparisonResponseId ?: "null"))
                        add(JsonPrimitive(promptCacheKey ?: "null"))
                        add(JsonPrimitive(promptCacheTtl ?: "null"))
                        add(JsonPrimitive("streaming:true"))
                    })
                    val persistProviderAttempt: suspend () -> Unit = {
                        log.append("request/provider-attempt", buildJsonObject {
                            put("version", PROVIDER_ATTEMPT_EVIDENCE_VERSION)
                            put("request_uid", requestUid)
                            put("header_seq", requestHeader.sequence)
                            put("attempt", providerAttempt)
                            put("recovery_round", overflowRound)
                            put("model", runSurface.model)
                            put("base_url", runSurface.baseUrl)
                            put("profile_id", frozenProfile.id)
                            put("provider", frozenProfile.provider)
                            put("protocol", runSurface.protocol.name)
                            put("route_fingerprint", routeFingerprint)
                            put("streaming", true)
                            put("image_generation_requested", allowImageGeneration)
                            put("image_generation_enabled", effectiveImageGeneration)
                            put("native_tool_names", nativeTools)
                            effectiveTemperature?.let { put("temperature", it) }
                            deepSeekMaxTokens?.let { put("max_tokens", it) }
                            cacheComparisonResponseId?.let {
                                put("prompt_cache_comparison_response_id", it)
                            }
                            promptCacheKey?.let { put("prompt_cache_key", it) }
                            promptCacheTtl?.let { put("prompt_cache_ttl", it) }
                            put("message_digest", activeEvidence.messageDigest)
                            put("message_surface_digest", activeReplayEvidence.messageDigest)
                            put("message_surface_seq", activeMessageSurfaceSeq)
                            put(
                                "message_surface_redacted",
                                activeReplayEvidence.messageDigest != activeEvidence.messageDigest,
                            )
                            put("context_digest", activeEvidence.contextDigest)
                            put("context_surface_digest", activeReplayEvidence.contextDigest)
                            put("context_surface_seq", activeContextSurfaceSeq)
                            put(
                                "context_surface_redacted",
                                activeReplayEvidence.contextDigest != activeEvidence.contextDigest,
                            )
                            put("tool_schema_digest", activeEvidence.toolSchemaDigest)
                            put("resolved_tool_surface_digest", activeEvidence.resolvedToolSurfaceDigest)
                            put("tool_surface_seq", toolSurfaceSeq)
                            put("provider_attempt_fingerprint", providerAttemptFingerprint)
                        })
                    }
                    val previewDeltaTransform = options.previewDeltaTransformFactory?.invoke()
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
                    // Checkpoint only the same filtered Chat text sent to the visible preview.
                    // Chunking avoids repeatedly fsync-ing a growing 65 KiB snapshot.
                    val durableChatPreview = if (snapshot.usageMode == LocalUsageMode.CHAT &&
                        step == 1 && publishPreviewEnabled) LocalChatStreamCheckpointWriter(
                        log = log,
                        requestUid = requestUid,
                        onFailure = { failure ->
                            AppLog.warn("LocalModelRequest", "chat_stream_checkpoint_failed", failure)
                        },
                    ) else null
                    // Every protocol already emits normalized content/reasoning deltas.
                    // Publish both through the same request-owned, bounded preview pipeline.
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
                                temperature = effectiveTemperature,
                                reasoningEffort = reasoningEffort,
                                maxTokens = deepSeekMaxTokens,
                                promptCacheComparisonResponseId = cacheComparisonResponseId,
                                promptCacheKey = promptCacheKey,
                            promptCacheTtl = promptCacheTtl,
                            allowImageGeneration = effectiveImageGeneration,
                            admission = admission,
                            beforeProviderInvoke = {
                                persistProviderAttempt()
                                providerStartedNanos = System.nanoTime()
                            },
                            onDelta = { delta ->
                                    val visible = delta.content
                                    attemptEvidence.observe(delta)
                                    val visibleDelta = previewDeltaTransform?.invoke(visible) ?: visible
                                    durableChatPreview?.append(visibleDelta)
                                    streamPreview.append(visibleDelta)
                                    reasoningPreview.append(delta.reasoning)
                                },
                            )
                        } catch (cancelled: CancellationException) {
                            val admissionState =
                                (cancelled as? LocalModelCancellationException)?.admissionState
                            log.append("request/cancelled", buildJsonObject {
                                put("request_uid", requestUid)
                                put("step", step)
                                put("cancellation_type", cancelled::class.java.simpleName)
                                providerStartedNanos?.let { put("provider_execution_ms",
                                    (System.nanoTime() - it).coerceAtLeast(0L) / 1_000_000) }
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
                                put("origin", localModelRequestErrorOrigin(error.code))
                                put("step", step)
                                put("code", error.code)
                                error.status?.let { put("status", it) }
                                error.providerRetryAfterMs?.let { put("retry_after_ms", it) }
                                error.requestId?.let { put("request_id", it) }
                                error.providerCode?.let { put("provider_code", it) }
                                error.providerParam?.let { put("provider_param", it) }
                                put("failure_kind", modelFailureKind(error))
                                modelTransportFailureKind(error)?.let { put("transport_kind", it) }
                                put("network_path_at_start", networkPathAtStart)
                                put("network_path_at_failure", describeModelNetworkPath(networkContext))
                                put("admission_state", error.admissionState.name.lowercase())
                                put("continuation_eligible", error.continuationEligible)
                                error.cause?.let { put("cause_type", it::class.java.simpleName) }
                                providerStartedNanos?.let { put("provider_execution_ms",
                                    (System.nanoTime() - it).coerceAtLeast(0L) / 1_000_000) }
                                error.cause?.message?.takeIf(String::isNotBlank)?.let {
                                    put("cause_detail", it.take(800))
                                }
                            })
                            throw error
                        } finally {
                            durableChatPreview?.flush()
                        }
                            streamPreview.flush()
                            reasoningPreview.flush()
                            activePrefixAssessment?.let { cache ->
                                promptCacheContinuity.recordSuccess(
                                    snapshot.sessionId,
                                    routeFingerprint,
                                    activeMessages,
                                    tools,
                                    cache.generation,
                                    nativeTools,
                                    diagnosticSurface = cacheDiagnosticSurface,
                                )
                            }
                            val rawAcceptedEstimate = activePressure.estimatedInputTokens
                            if (reply.usage.reported) {
                                pressureStore.recordReportedUsage(
                                    snapshot.sessionId, reply.usage.promptTokens,
                                    routeFingerprint = routeFingerprint,
                                    rawEstimatedInputTokens = rawAcceptedEstimate,
                                )
                            }
                            log.append("request/completed", buildJsonObject {
                                put("raw_estimated_prompt_tokens", rawAcceptedEstimate)
                                pressureStore.routeCalibration(routeFingerprint)?.let { (samples, permille) ->
                                    put("route_calibration_samples", samples)
                                    put("route_soft_estimate_scale_permille", permille)
                                }
                                put("request_uid", requestUid)
                                put("request_envelope_fingerprint", requestEnvelopeFingerprint)
                                put("header_seq", requestHeader.sequence)
                                put("step", step)
                                put("request_id", reply.requestId)
                                put("completion_reason", reply.completionReason.name)
                                reply.rawStopReason?.let { put("provider_stop_reason", it) }
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
        }
        } finally {
            previewOwner?.let(streamingPreviewStore::clear)
        }
    }

    private fun stablePromptCacheKey(sessionId: String, routeFingerprint: String): String {
        val raw = sessionId + "\u0000" + routeFingerprint
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(64)
    }

    private companion object {
        const val REQUEST_EVIDENCE_VERSION = 3
        const val PROVIDER_ATTEMPT_EVIDENCE_VERSION = 2
    }
}

/** Coarse network facts only; never logs addresses, Wi-Fi names or VPN node identifiers. */
private fun describeModelNetworkPath(context: Context): String = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching "unavailable"
    val active = manager.activeNetwork ?: return@runCatching "offline"
    val caps = manager.getNetworkCapabilities(active) ?: return@runCatching "unknown"
    buildList {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        if (isEmpty()) add("other")
        add(if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "unvalidated")
        if (manager.getLinkProperties(active)?.httpProxy != null) add("system_proxy")
    }.joinToString("+")
}.getOrDefault("unavailable")
