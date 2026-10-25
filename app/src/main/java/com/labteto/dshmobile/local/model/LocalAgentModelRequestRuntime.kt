package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.harness.agent.AgentRunExecutionContext
import kotlinx.coroutines.currentCoroutineContext
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.documentedContextWindowTokens
import com.labteto.dshmobile.local.operationalInputLimitTokens
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal data class LocalModelAdmissionRequest(
    val routeFingerprint: String,
    val model: String,
    val baseUrl: String,
    val contextWindowTokensOverride: Int?,
    val messages: List<JsonObject>,
    val tools: JsonArray,
    /** Optional frozen route-derived context policy; legacy admission callers may omit it. */
    val operationalInputLimitSnapshot: Int? = null,
    val contextWindowSnapshot: Int? = null,
)

internal fun interface LocalModelAdmissionPort {
    suspend fun execute(
        request: LocalModelAdmissionRequest,
        block: suspend () -> LocalModelReply,
    ): LocalModelReply
}

/**
 * E11 resource attribution: one actual model lease follows its original run slot
 * without a new scheduler, database or duplicated activity counter. The active
 * scheduler continues to own allocation and release.
 */
internal fun localAgentModelResourceOwner(runId: String?, slotId: String?): String {
    val run = runId?.takeIf(String::isNotBlank) ?: return "model-request:unbound"
    val slot = slotId?.takeIf(String::isNotBlank) ?: "unknown"
    return "agent-model:${slot.take(64)}:${run.take(64)}"
}

/** Model-owned safety preflight that applies to every provider request, with or without Work. */
internal fun validateModelRequestAdmission(request: LocalModelAdmissionRequest) {
    val pressure = LocalPromptPressureMeter.measure(
        messages = request.messages,
        tools = request.tools,
        operationalLimitTokens = request.operationalInputLimitSnapshot
            ?: operationalInputLimitTokens(request.model, request.baseUrl, request.contextWindowTokensOverride),
        modelContextWindowTokens = request.contextWindowSnapshot
            ?: documentedContextWindowTokens(request.model, request.baseUrl, request.contextWindowTokensOverride),
    )
    if (pressure.estimatedInputTokens > pressure.operationalLimitTokens) {
        throw LocalModelException(
            code = "MODEL_CONTEXT_BUDGET_EXCEEDED",
            message = "预计输入 ${pressure.estimatedInputTokens} token，超过当前路由安全上限 ${pressure.operationalLimitTokens}",
            retryable = false,
        )
    }
}

/**
 * Shared admitted provider-call boundary for foreground agents, subagents and bounded auxiliary work.
 *
 * Foreground/subagent callers pass the Shared Runtime resource scheduler. Auxiliary calls may omit it;
 * they still share frozen route, context admission and provider invocation without creating a second
 * resource-scheduling fact source.
 */
internal class LocalAgentModelRequestRuntime(
    private val modelGateway: LocalModelGateway,
    private val resourceScheduler: HarnessResourceScheduler? = null,
) {
    suspend fun complete(
        surface: LocalRunModelSurface,
        messages: List<JsonObject>,
        tools: JsonArray,
        streaming: Boolean = false,
        temperature: Double? = null,
        reasoningEffort: String? = null,
        promptCacheComparisonResponseId: String? = null,
        promptCacheKey: String? = null,
        promptCacheTtl: String? = null,
        allowImageGeneration: Boolean = false,
        maxTokens: Int? = null,
        admission: LocalModelAdmissionPort? = null,
        beforeProviderInvoke: suspend () -> Unit = {},
        onDelta: (LocalModelDelta) -> Unit = {},
    ): LocalModelReply {
        val completeProvider: suspend () -> LocalModelReply = {
            beforeProviderInvoke()
            if (streaming) {
                modelGateway.completeStreaming(
                    model = surface.model,
                    baseUrl = surface.baseUrl,
                    messages = messages,
                    tools = tools,
                    temperature = temperature,
                    reasoningEffort = reasoningEffort,
                    profile = surface.profile,
                    promptCacheComparisonResponseId = promptCacheComparisonResponseId,
                    promptCacheKey = promptCacheKey,
                    promptCacheTtl = promptCacheTtl,
                    allowImageGeneration = allowImageGeneration,
                    maxTokens = maxTokens,
                    onDelta = onDelta,
                )
            } else {
                modelGateway.complete(
                    model = surface.model,
                    baseUrl = surface.baseUrl,
                    messages = messages,
                    tools = tools,
                    temperature = temperature,
                    reasoningEffort = reasoningEffort,
                    profile = surface.profile,
                    promptCacheComparisonResponseId = promptCacheComparisonResponseId,
                    promptCacheKey = promptCacheKey,
                    promptCacheTtl = promptCacheTtl,
                    allowImageGeneration = allowImageGeneration,
                    maxTokens = maxTokens,
                )
            }
        }
        val invokeProvider: suspend () -> LocalModelReply = {
            val scheduler = resourceScheduler
            if (scheduler != null) {
                val run = currentCoroutineContext()[AgentRunExecutionContext]
                val owner = localAgentModelResourceOwner(
                    run?.identity?.runId, run?.identity?.slotId,
                )
                scheduler.withResource(HarnessResourceKind.MODEL_REQUEST, owner = owner) {
                    completeProvider()
                }
            } else {
                completeProvider()
            }
        }
        val admissionRequest = LocalModelAdmissionRequest(
            routeFingerprint = surface.routeFingerprint,
            model = surface.model,
            baseUrl = surface.baseUrl,
            contextWindowTokensOverride = surface.contextWindowTokensOverride,
            messages = messages,
            tools = tools,
            operationalInputLimitSnapshot = surface.contract.operationalInputLimitTokens,
            contextWindowSnapshot = surface.contract.documentedContextWindowTokens,
        )
        validateModelRequestAdmission(admissionRequest)
        return admission?.execute(admissionRequest, invokeProvider) ?: invokeProvider()
    }
}
