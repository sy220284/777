package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.model.LocalWorkContextAssessmentSnapshot
import com.labteto.dshmobile.local.runtime.LocalEnvironmentRunSnapshot
import com.labteto.dshmobile.local.runtime.LocalEnvironmentWorkBudget
import com.labteto.dshmobile.local.runtime.LocalEnvironmentWorkContextAssessment

/**
 * WorkFeature contributes a read-only diagnostic projection to Shared Runtime.
 * Shared Runtime never receives the Work binding or any Work-owned mutable state.
 */
internal fun LocalWorkRunBinding.toEnvironmentRunSnapshot(
    contextBudgetChars: Int,
): LocalEnvironmentRunSnapshot {
    val optionalTools = synchronized(enabledOptionalTools) { enabledOptionalTools.toSet() }
    return LocalEnvironmentRunSnapshot(
        sessionId = sessionId,
        contextChars = modelHistory.encodedChars,
        contextBudgetChars = contextBudgetChars,
        pendingInputs = pendingInputs.size(),
        enabledOptionalTools = optionalTools,
        workBudget = executionControl.budget.snapshot().toEnvironmentWorkBudget(),
    )
}

internal fun LocalWorkContextAssessmentSnapshot.toEnvironmentWorkContextAssessment() =
    LocalEnvironmentWorkContextAssessment(
        status = status,
        historyRatioPermille = historyRatioPermille,
        toolRatioPermille = toolRatioPermille,
        inputGrowthTokens = inputGrowthTokens,
        historyGrowthTokens = historyGrowthTokens,
        effectiveProjectionTriggerTokens = effectiveProjectionTriggerTokens,
        reasons = reasons,
    )

internal fun LocalWorkExecutionBudget.Snapshot.toEnvironmentWorkBudget() =
    LocalEnvironmentWorkBudget(
        reportedExposureTokens = reportedExposureTokens,
        uncertainExposureTokens = uncertainExposureTokens,
        pendingExposureTokens = pendingExposureTokens,
        admittedRequests = admittedRequests,
        maxRequests = maxRequests,
        estimateCalibrationPermille = estimateCalibrationPermille,
        calibrationSamples = calibrationSamples,
        calibrationRoutes = calibrationRoutes,
    )
