package com.labteto.dshmobile.local.context

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalPromptCachePolicy
import com.labteto.dshmobile.local.model.LocalPromptPressure
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Work-agnostic diagnostics emitted by an optional Feature-owned semantic projector. */
internal data class LocalRequestContextAssessment(
    val status: String,
    val estimatedInputTokens: Int = 0,
    val historyTokens: Int = 0,
    val toolDefinitionTokens: Int = 0,
    val historyRatioPermille: Int,
    val toolRatioPermille: Int = 0,
    val inputGrowthTokens: Int = 0,
    val historyGrowthTokens: Int,
    val effectiveProjectionTriggerTokens: Int,
    val reasons: List<String>,
)

/** Shared request-context result. Feature-specific projection policy is supplied by composition. */
internal data class LocalRequestContextProjection(
    val messages: List<JsonObject>,
    val projected: Boolean,
    val estimatedTokensBefore: Int,
    val estimatedTokensAfter: Int,
    val omittedMessages: Int = 0,
    val preProjectionAssessment: LocalRequestContextAssessment? = null,
)

internal data class LocalRequestContextPolicyInput(
    val snapshot: LocalHarnessState,
    val eventLog: LocalSessionEventLog,
    val messages: List<JsonObject>,
    val tools: JsonArray,
    val operationalLimitTokens: Int,
    val measuredPressure: LocalPromptPressure,
    val previousPressure: LocalPromptPressure?,
    val cachePolicy: LocalPromptCachePolicy,
    val allowSemanticProjection: Boolean,
)

internal data class LocalRequestContextAssessmentInput(
    val current: LocalPromptPressure,
    val previous: LocalPromptPressure?,
    val operationalLimitTokens: Int,
    val cachePolicy: LocalPromptCachePolicy,
    val growthCurrent: LocalPromptPressure,
)

/**
 * Shared context extension point. Product Features supply semantic projection/assessment without
 * making Shared Model/Context import Feature internals.
 */
internal interface LocalRequestContextPolicy {
    fun project(input: LocalRequestContextPolicyInput): LocalRequestContextProjection

    fun assess(input: LocalRequestContextAssessmentInput): LocalRequestContextAssessment?
}

/**
 * Shared mode gate for request-context governance.
 *
 * Shared Context owns only the neutral decision surface. WorkFeature supplies its semantic
 * projection through [workProjection]; Chat keeps its own history policy and never imports Work
 * internals through this package.
 */
internal fun projectLocalRequestContext(
    usageMode: LocalUsageMode,
    workProjectionEnabled: Boolean,
    messages: List<JsonObject>,
    measuredPressure: LocalPromptPressure,
    workProjection: (() -> LocalRequestContextProjection)?,
): LocalRequestContextProjection {
    if (
        usageMode != LocalUsageMode.WORK ||
        !workProjectionEnabled ||
        workProjection == null
    ) {
        return LocalRequestContextProjection(
            messages = messages,
            projected = false,
            estimatedTokensBefore = measuredPressure.estimatedInputTokens,
            estimatedTokensAfter = measuredPressure.estimatedInputTokens,
        )
    }
    return workProjection()
}
