package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.context.LocalRequestContextAssessment
import com.labteto.dshmobile.local.context.LocalRequestContextAssessmentInput
import com.labteto.dshmobile.local.context.LocalRequestContextPolicy
import com.labteto.dshmobile.local.context.LocalRequestContextPolicyInput
import com.labteto.dshmobile.local.context.LocalRequestContextProjection
import com.labteto.dshmobile.local.model.LocalHistoryCompactor
import com.labteto.dshmobile.local.model.LocalStructuredWorkState
import com.labteto.dshmobile.local.model.LocalWorkContextAssessmentSnapshot
import com.labteto.dshmobile.local.session.LocalSessionEventLog

/** WorkFeature implementation of the Shared request-context policy contract. */
internal object LocalWorkRequestContextPolicy : LocalRequestContextPolicy {
    private val compactor = LocalHistoryCompactor()

    override fun project(input: LocalRequestContextPolicyInput): LocalRequestContextProjection {
        val projected = projectWorkRequestContext(
            messages = input.messages,
            tools = input.tools,
            compactor = compactor,
            operationalLimitTokens = input.operationalLimitTokens,
            measuredPressure = input.measuredPressure,
            previousPressure = input.previousPressure,
            structuredWorkState = structuredWorkState(input.snapshot, input.eventLog),
            cachePolicy = input.cachePolicy,
            allowSemanticProjection = input.allowSemanticProjection,
        )
        return LocalRequestContextProjection(
            messages = projected.messages,
            projected = projected.projected,
            estimatedTokensBefore = projected.estimatedTokensBefore,
            estimatedTokensAfter = projected.estimatedTokensAfter,
            omittedMessages = projected.omittedMessages,
            preProjectionAssessment = projected.preProjectionAssessment?.let { assessment ->
                LocalRequestContextAssessment(
                    status = assessment.status.name.lowercase(),
                    effectiveProjectionTriggerTokens = assessment.effectiveProjectionTriggerTokens,
                    historyRatioPermille = assessment.historyRatioPermille,
                    historyGrowthTokens = assessment.historyGrowthTokens,
                    reasons = assessment.reasons,
                )
            },
        )
    }

    override fun assess(
        input: LocalRequestContextAssessmentInput,
    ): LocalWorkContextAssessmentSnapshot =
        assessWorkStepContext(
            current = input.current,
            previous = input.previous,
            targetTokens = workRequestProjectionTargetTokens(
                input.operationalLimitTokens,
                input.cachePolicy,
            ),
            baseTriggerTokens = workRequestProjectionTriggerTokens(
                input.operationalLimitTokens,
                input.cachePolicy,
            ),
            growthCurrent = input.growthCurrent,
            allowAdaptiveEarlyCompaction = input.cachePolicy.allowAdaptiveEarlyCompaction,
        ).toModelSnapshot()

    override fun structuredState(
        snapshot: LocalHarnessState,
        eventLog: LocalSessionEventLog,
    ): LocalStructuredWorkState = structuredWorkState(snapshot, eventLog)
}
