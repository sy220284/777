package com.labteto.dshmobile.local.context

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalPromptPressure
import kotlinx.serialization.json.JsonObject

/** Work-agnostic diagnostics emitted by an optional Feature-owned semantic projector. */
internal data class LocalRequestContextAssessment(
    val status: String,
    val effectiveProjectionTriggerTokens: Int,
    val historyRatioPermille: Int,
    val historyGrowthTokens: Int,
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
