package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.local.model.LocalModelGateway
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Shared physical provider-call boundary for foreground agents and subagents.
 *
 * Retry, admission, projection and recovery stay with their existing policy owners for now. The
 * frozen route, resource lease and actual provider invocation already share one implementation,
 * giving later request-lifecycle convergence a stable seam without changing current retry behavior.
 */
internal class LocalAgentModelRequestRuntime(
    private val modelGateway: LocalModelGateway,
    private val resourceScheduler: HarnessResourceScheduler,
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
        onDelta: (LocalModelDelta) -> Unit = {},
    ): LocalModelReply = resourceScheduler.withResource(HarnessResourceKind.MODEL_REQUEST) {
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
}
