package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.local.model.LocalModelGateway
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Shared admitted provider-call boundary for foreground agents, subagents and bounded auxiliary work.
 *
 * Foreground/subagent callers pass the Engine-owned resource scheduler. Auxiliary calls may omit it;
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
        executionControl: LocalWorkExecutionControl? = null,
        admissionHandledExternally: Boolean = false,
        onDelta: (LocalModelDelta) -> Unit = {},
    ): LocalModelReply {
        val completeProvider: suspend () -> LocalModelReply = {
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
        if (admissionHandledExternally) return invokeProvider()
        return executeWithModelAdmission(
            control = executionControl,
            routeFingerprint = surface.routeFingerprint,
            model = surface.model,
            baseUrl = surface.baseUrl,
            contextWindowTokensOverride = surface.contextWindowTokensOverride,
            messages = messages,
            tools = tools,
        ) {
            invokeProvider()
        }
    }
}
