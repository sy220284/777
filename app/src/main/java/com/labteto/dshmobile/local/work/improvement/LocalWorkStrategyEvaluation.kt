package com.labteto.dshmobile.local.work.improvement

import com.labteto.dshmobile.harness.optimization.StrategyComparison
import com.labteto.dshmobile.harness.optimization.StrategyTrial
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageAnalyticsStore
import com.labteto.dshmobile.local.TokenUsageGroupKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.work.LocalWorkRunBinding
import com.labteto.dshmobile.local.work.localWorkDeliveryEvidence
import com.labteto.dshmobile.local.model.routeFingerprint
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Compares two user-requested runs with the identical Work Goal acceptance signature.
 * Does not replay tools or make a provider request. A narrower measurable improvement may
 * be adopted ONLY after real test receipts and attributable usage for both executions.
 */
internal class LocalWorkStrategyEvaluation(
    private val sessionStorage: LocalSessionStorageRuntime,
    private val usage: TokenUsageAnalyticsStore,
) {
    fun evaluate(bound: LocalWorkRunBinding, runId: String, advice: WorkImprovementAdvice) {
        val log = bound.eventLog
        val goal = bound.workState.snapshot().goal ?: return
        log.latestMatching(setOf(
            if (advice.active) "work/improvement-applied" else "work/improvement-trial",
        )) {
            it.value("run_id") == runId && it.value("candidate_id") == advice.id &&
                it.value("goal_id") == goal.id &&
                it.value("steering_revision") == bound.steeringRevision.value.toString()
        } ?: return
        val evidence = localWorkDeliveryEvidence(log, goal, File(sessionStorage.files.workspace.path))
        val current = bound.aggregateSnapshot()
        if (current.modelState.model != advice.model ||
            (current.modelState.modelSelection.activeProfileId ?: "") != advice.profileId ||
            current.modelState.modelSelection.activeProfile?.routeFingerprint().orEmpty() != advice.routeFingerprint ||
            (current.projectId ?: "") != advice.projectId) return
        // A blocked Goal with a recorded failed tool outcome is real negative evidence.
        // It is not a proven causal attribution, so record it as an observation and
        // suspend this advice rather than allowing indefinite reuse as 'adopted'.
        if (workGoalComparisonKey(goal) != advice.goalSignature || !advice.baselineTestVerified) return
        val (_, failedTools) = workGoalToolMetrics(log, goal)
        if (goal.status == "blocked" && failedTools > 0) {
            val lease = LocalSessionRuntimeRegistry.tryAcquire(
                bound.sessionId, LocalSessionRuntimeKind.MAINTENANCE,
            ) ?: return
            try {
                if (bound.workState.snapshot().goal != goal) return
                log.withWriterLease {
                    if (log.latestMatching(setOf("work/improvement-evaluation")) {
                            it.value("run_id") == runId && it.value("candidate_id") == advice.id
                        } != null) return@withWriterLease
                    log.append("work/improvement-evaluation", buildJsonObject {
                        put("candidate_id", advice.id)
                        put("run_id", runId)
                        put("goal_id", goal.id)
                        put("goal_signature", advice.goalSignature)
                        put("disposition", "quality_regression")
                        put("comparison", "observed_blocked_goal_not_causal_attribution")
                        put("candidate_tool_errors", failedTools)
                        put("source_receipt", evidence.testCallId.orEmpty())
                    })
                    log.append("work/improvement-retired", buildJsonObject {
                        put("candidate_id", advice.id)
                        put("run_id", runId)
                        put("reason", "observed_failed_acceptance")
                    })
                }
            } finally { lease.close() }
            return
        }
        if (goal.status != "completed" ||
            evidence.missing.isNotEmpty() || evidence.testCallId == null) return

        // Real immutable Usage database is the only token accounting source; missing history
        // is INSUFFICIENT EVIDENCE, not zero cost.
        fun tokens(run: String): Long? {
            val details = usage.groupDetail(TokenUsageGroupKind.TASK, run) ?: return null
            val rows = details.actions.filter { it.action != TokenUsageAction.WORK_IMPROVEMENT }
            if (rows.isEmpty()) return null
            return rows.sumOf { it.aggregate.totalTokens }
        }
        val baselineTokens = tokens(advice.sourceTaskRunId.ifBlank { advice.sourceRunId }) ?: return
        val trialTaskRunId = com.labteto.dshmobile.local.usage.resolveTokenUsageTaskRunId(log, runId)
        val observedTokens = tokens(trialTaskRunId ?: return) ?: return
        val (tools, failures) = workGoalToolMetrics(log, goal)
        val testKey = advice.goalSignature
        val result = StrategyComparison.compare(
            baseline = StrategyTrial(advice.sourceRunId, 1,
                setOf(testKey), setOf(testKey), true,
                advice.baselineToolErrors, advice.baselineToolCalls.toLong(), baselineTokens),
            candidate = StrategyTrial(runId, 1,
                setOf(testKey), setOf(testKey), true,
                failures, tools.toLong(), observedTokens),
        )
        val lease = LocalSessionRuntimeRegistry.tryAcquire(bound.sessionId, LocalSessionRuntimeKind.MAINTENANCE)
            ?: return
        try {
            if (bound.workState.snapshot().goal != goal) return
            log.withWriterLease {
                if (log.latestMatching(setOf("work/improvement-evaluation")) {
                        it.value("run_id") == runId && it.value("candidate_id") == advice.id
                    } != null) return@withWriterLease
                log.append("work/improvement-evaluation", buildJsonObject {
                    put("candidate_id", advice.id)
                    put("run_id", runId)
                    put("goal_id", goal.id)
                    put("goal_signature", testKey)
                    put("baseline_tool_calls", advice.baselineToolCalls)
                    put("candidate_tool_calls", tools)
                    put("baseline_tool_errors", advice.baselineToolErrors)
                    put("candidate_tool_errors", failures)
                    put("baseline_tokens", baselineTokens)
                    put("candidate_tokens", observedTokens)
                    put("disposition", result.disposition.name.lowercase())
                    put("comparison", "natural_same_acceptance_observational")
                    put("source_receipt", evidence.testCallId)
                })
                if (!advice.active && result.canAdopt) {
                    // Narrow acceptance only: exact Goal contract, project and model/profile.
                    // Never mutate stable System Prompt, skill files, approvals or tool policy.
                    log.append("work/improvement-adopted", buildJsonObject {
                        put("candidate_id", advice.id)
                        put("run_id", runId)
                        put("category", advice.category)
                        put("goal_signature", advice.goalSignature)
                        put("model", advice.model)
                        put("profile_id", advice.profileId)
                        put("project_id", advice.projectId)
                        put("evaluation", "verified_narrow_scope")
                    })
                } else if (shouldRetireWorkAdvice(
                        active = advice.active,
                        comparison = result.disposition,
                        candidateToolCalls = tools.toLong(),
                        baselineToolCalls = advice.baselineToolCalls.toLong(),
                        candidateTokens = observedTokens,
                        baselineTokens = baselineTokens,
                    )) {
                    log.append("work/improvement-retired", buildJsonObject {
                        put("candidate_id", advice.id)
                        put("run_id", runId)
                        put("reason", if (result.disposition ==
                            com.labteto.dshmobile.harness.optimization.StrategyComparisonDisposition.QUALITY_REGRESSION)
                            "quality_regression" else "verified_efficiency_regression")
                    })
                }
            }
        } finally { lease.close() }
    }
}

/** A published advice is kept only while its real outcomes stay safe and useful. */
internal fun shouldRetireWorkAdvice(
    active: Boolean,
    comparison: com.labteto.dshmobile.harness.optimization.StrategyComparisonDisposition,
    candidateToolCalls: Long,
    baselineToolCalls: Long,
    candidateTokens: Long,
    baselineTokens: Long,
): Boolean = comparison ==
    com.labteto.dshmobile.harness.optimization.StrategyComparisonDisposition.QUALITY_REGRESSION ||
    (active && candidateToolCalls > baselineToolCalls && candidateTokens > baselineTokens)

private fun JsonObject.value(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
