package com.labteto.dshmobile.harness.optimization

/**
 * Platform/Feature-neutral evidence assessment. Scoring and admission never invoke a model,
 * run tools, store sessions, edit prompts, or decide a Feature's acceptance semantics.
 */
data class StrategyTrial(
    val id: String,
    val version: Long,
    val evaluatedTaskIds: Set<String>,
    val passedTaskIds: Set<String>,
    val independentVerification: Boolean,
    val failureCount: Int,
    val toolCalls: Long,
    val reportedTokens: Long,
) {
    init {
        require(id.isNotBlank() && version > 0)
        require(failureCount >= 0 && toolCalls >= 0 && reportedTokens >= 0)
        require(passedTaskIds.all(evaluatedTaskIds::contains))
    }
}

enum class StrategyComparisonDisposition {
    ADOPTABLE, INSUFFICIENT_EVIDENCE, QUALITY_REGRESSION, NO_MEASURED_BENEFIT
}

data class StrategyComparisonResult(
    val disposition: StrategyComparisonDisposition,
    val evaluatedTasks: Int,
    val baselinePasses: Int,
    val candidatePasses: Int,
) {
    val canAdopt: Boolean get() = disposition == StrategyComparisonDisposition.ADOPTABLE
}

/**
 * Same independent task identities, externally verified tests, no loss of passed test cases.
 * Effect is either an actual additional success with no regressions or strictly lower overhead
 * on equally successful tasks (tool calls or Token count without making the other worse).
 * The baseline is always retained unless the candidate provides measurable improvement.
 */
object StrategyComparison {
    fun compare(baseline: StrategyTrial, candidate: StrategyTrial): StrategyComparisonResult {
        val paired = baseline.evaluatedTaskIds.intersect(candidate.evaluatedTaskIds)
        val baselineWins = baseline.passedTaskIds.intersect(paired)
        val candidateWins = candidate.passedTaskIds.intersect(paired)
        val disposition = when {
            paired.isEmpty() || !baseline.independentVerification || !candidate.independentVerification ||
                baseline.evaluatedTaskIds != paired || candidate.evaluatedTaskIds != paired ->
                StrategyComparisonDisposition.INSUFFICIENT_EVIDENCE
            !candidateWins.containsAll(baselineWins) ||
                candidate.failureCount > baseline.failureCount ->
                StrategyComparisonDisposition.QUALITY_REGRESSION
            candidateWins.size > baselineWins.size ->
                StrategyComparisonDisposition.ADOPTABLE
            candidateWins.size == paired.size && candidate.failureCount <= baseline.failureCount &&
                candidate.toolCalls <= baseline.toolCalls && candidate.reportedTokens <= baseline.reportedTokens &&
                (candidate.toolCalls < baseline.toolCalls || candidate.reportedTokens < baseline.reportedTokens) ->
                StrategyComparisonDisposition.ADOPTABLE
            else -> StrategyComparisonDisposition.NO_MEASURED_BENEFIT
        }
        return StrategyComparisonResult(disposition, paired.size, baselineWins.size, candidateWins.size)
    }
}
