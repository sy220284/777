package com.labteto.dshmobile.harness.optimization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyComparisonTest {
    private fun trial(
        id: String, tasks: Set<String> = setOf("alpha", "beta"),
        passed: Set<String> = tasks, independent: Boolean = true,
        failures: Int = 0, tools: Long = 8, tokens: Long = 1200,
    ) = StrategyTrial(id, 1, tasks, passed, independent, failures, tools, tokens)

    @Test fun independentlyVerifiedLowerCostCanWin() {
        assertTrue(StrategyComparison.compare(trial("base"), trial("candidate", tools = 6, tokens = 1000)).canAdopt)
    }

    @Test fun noEvidenceNeverAdoptsSoleCandidate() {
        assertEquals(StrategyComparisonDisposition.INSUFFICIENT_EVIDENCE,
            StrategyComparison.compare(trial("base"), trial("x", independent = false)).disposition)
    }

    @Test fun mismatchedTasksAreIncomparable() {
        assertEquals(StrategyComparisonDisposition.INSUFFICIENT_EVIDENCE,
            StrategyComparison.compare(trial("base"), trial("x", setOf("alpha"))).disposition)
    }

    @Test fun successRegressionRejectsEvenIfCostImproves() {
        assertEquals(StrategyComparisonDisposition.QUALITY_REGRESSION,
            StrategyComparison.compare(trial("base"), trial("x", passed = setOf("alpha"), tools = 1, tokens = 1)).disposition)
    }

    @Test fun noBenefitRetainsBaseline() {
        assertFalse(StrategyComparison.compare(trial("base"), trial("x")).canAdopt)
    }

    @Test fun actuallyAdditionalVerifiedSuccessCanWin() {
        assertTrue(StrategyComparison.compare(
            trial("base", passed = setOf("alpha")),
            trial("x", passed = setOf("alpha", "beta"), tools = 100)).canAdopt)
    }
}
