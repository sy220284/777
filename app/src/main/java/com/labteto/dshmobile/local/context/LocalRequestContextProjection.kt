package com.labteto.dshmobile.local.context

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalHistoryCompactor
import com.labteto.dshmobile.local.model.LocalPromptCachePolicy
import com.labteto.dshmobile.local.model.LocalPromptPressure
import com.labteto.dshmobile.local.model.LocalStructuredWorkState
import com.labteto.dshmobile.local.runtime.structuredWorkState
import com.labteto.dshmobile.local.work.LocalWorkStepContextAssessment
import com.labteto.dshmobile.local.work.projectWorkRequestContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Shared request-context governance entry.
 *
 * Mode policy remains explicit: Work currently owns semantic steady-state projection; Chat keeps
 * its existing history policy and consumes the shared pressure/cache/overflow infrastructure.
 */
internal data class LocalRequestContextProjection(
    val messages: List<JsonObject>,
    val projected: Boolean,
    val estimatedTokensBefore: Int,
    val estimatedTokensAfter: Int,
    val omittedMessages: Int = 0,
    val preProjectionAssessment: LocalWorkStepContextAssessment? = null,
)

internal fun projectLocalRequestContext(
    usageMode: LocalUsageMode,
    workProjectionEnabled: Boolean,
    messages: List<JsonObject>,
    tools: JsonArray,
    compactor: LocalHistoryCompactor,
    operationalLimitTokens: Int,
    measuredPressure: LocalPromptPressure,
    previousSourcePressure: LocalPromptPressure?,
    structuredWorkState: LocalStructuredWorkState?,
    cachePolicy: LocalPromptCachePolicy,
    allowSemanticProjection: Boolean,
): LocalRequestContextProjection {
    if (usageMode != LocalUsageMode.WORK || !workProjectionEnabled) {
        return LocalRequestContextProjection(
            messages = messages,
            projected = false,
            estimatedTokensBefore = measuredPressure.estimatedInputTokens,
            estimatedTokensAfter = measuredPressure.estimatedInputTokens,
        )
    }

    val projected = projectWorkRequestContext(
        messages = messages,
        tools = tools,
        compactor = compactor,
        operationalLimitTokens = operationalLimitTokens,
        measuredPressure = measuredPressure,
        previousPressure = previousSourcePressure,
        structuredWorkState = structuredWorkState,
        cachePolicy = cachePolicy,
        allowSemanticProjection = allowSemanticProjection,
    )
    return LocalRequestContextProjection(
        messages = projected.messages,
        projected = projected.projected,
        estimatedTokensBefore = projected.estimatedTokensBefore,
        estimatedTokensAfter = projected.estimatedTokensAfter,
        omittedMessages = projected.omittedMessages,
        preProjectionAssessment = projected.preProjectionAssessment,
    )
}
