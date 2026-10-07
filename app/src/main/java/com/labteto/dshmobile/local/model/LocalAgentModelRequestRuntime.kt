package com.labteto.dshmobile.local.model

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
)

internal fun interface LocalModelAdmissionPort {
    suspend fun execute(
        request: LocalModelAdmissionRequest,
        block: suspend () -> LocalModelReply,
    ): LocalModelReply
}

/** Model-owned safety preflight that applies to every provider request, with or without Work. */
internal fun validateModelRequestAdmission(request: LocalModelAdmissionRequest) {
    val pressure = LocalPromptPressureMeter.measure(
        messages = request.messages,
        tools = request.tools,
        operationalLimitTokens = operationalInputLimitTokens(
            request.model,
            request.baseUrl,
            request.contextWindowTokensOverride,
        ),
        modelContextWindowTokens = documentedContextWindowTokens(
            request.model,
            request.baseUrl,
            request.contextWindowTokensOverride,
        ),
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
        promptCacheComparisonResponseId: String? = null,
        promptCacheKey: String? = null,
        promptCacheTtl: String? = null,
        allowImageGeneration: Boolean = false,
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
                    profile = surface.profile,
                    promptCacheComparisonResponseId = promptCacheComparisonResponseId,
                    promptCacheKey = promptCacheKey,
                    promptCacheTtl = promptCacheTtl,
                    allowImageGeneration = allowImageGeneration,
                    onDelta = onDelta,
                )
            } else {
                modelGateway.complete(
                    model = surface.model,
                    baseUrl = surface.baseUrl,
                    messages = messages,
                    tools = tools,
                    temperature = temperature,
                    profile = surface.profile,
                    promptCacheComparisonResponseId = promptCacheComparisonResponseId,
                    promptCacheKey = promptCacheKey,
                    promptCacheTtl = promptCacheTtl,
                    allowImageGeneration = allowImageGeneration,
                )
            }
        }
        val invokeProvider: suspend () -> LocalModelReply = {
            val scheduler = resourceScheduler
            if (scheduler != null) {
                scheduler.withResource(HarnessResourceKind.MODEL_REQUEST) { completeProvider() }
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
        )
        validateModelRequestAdmission(admissionRequest)
        return admission?.execute(admissionRequest, invokeProvider) ?: invokeProvider()
    }
}
