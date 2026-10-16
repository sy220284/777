package com.labteto.dshmobile.harness.session

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAttemptLedgerTest {
    @Test
    fun declaredToolAndActualExecutorAdmissionAreSeparateEvents() {
        val ledger = ToolAttemptLedger()
        ledger.observe("assistant/message", buildJsonObject {
            put("model_tool_calls", buildJsonArray {
                add(buildJsonObject { put("id", "call"); put("name", "write") })
            })
        }, stepHint = 3)
        ledger.observe("tool/call", buildJsonObject {
            put("id", "call")
            put("goal_id", "goal")
            put("execution_started", false)
        })
        assertFalse(ledger.hasUnresolvedExternalEffects("goal"))
        assertEquals(3, ledger.pending().single().step)
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "call")
        })
        assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
        ledger.observe("tool/result", buildJsonObject {
            put("id", "call")
            put("is_error", false)
            put("side_effect", "none")
        })
        assertFalse(ledger.hasUnresolvedExternalEffects("goal"))
        assertTrue(ledger.pending().isEmpty())
    }

    @Test
    fun unknownToolEffectSurvivesLaterSuccessfulResultAndIsGoalScoped() {
        val ledger = ToolAttemptLedger()
        ledger.observe("tool/call", buildJsonObject {
            put("id", "unknown")
            put("goal_id", "first")
            put("execution_started", false)
        })
        ledger.observe("tool/execution-started", buildJsonObject { put("id", "unknown") })
        ledger.observe("tool/result", buildJsonObject {
            put("id", "unknown")
            put("error_code", "TOOL_OUTCOME_UNKNOWN")
            put("side_effect", "possible")
            put("is_error", true)
        })
        ledger.observe("tool/call", buildJsonObject {
            put("id", "success")
            put("goal_id", "first")
            put("execution_started", false)
        })
        ledger.observe("tool/execution-started", buildJsonObject { put("id", "success") })
        ledger.observe("tool/result", buildJsonObject { put("id", "success"); put("is_error", false) })
        assertTrue(ledger.hasUnresolvedExternalEffects("first"))
        assertFalse(ledger.hasUnresolvedExternalEffects("other"))
        assertEquals(2, ledger.snapshot().size)
    }

    @Test
    fun legacyToolCallMeansStartedUntilDurableResultAndRecoveryCanSeeIt() {
        val ledger = ToolAttemptLedger()
        ledger.observe("tool/call", buildJsonObject { put("id", "old"); put("name", "write") })
        val attempt = ledger.pending().single()
        assertEquals("old", attempt.id)
        assertTrue(attempt.started)
        assertFalse(attempt.settled)
        ledger.observe("tool/result", buildJsonObject { put("id", "old") })
        assertTrue(ledger.pending().isEmpty())
    }

    @Test
    fun laterResultsNeverTurnAnUnadmittedCallIntoAnExecution() {
        val ledger = ToolAttemptLedger()
        ledger.observe("tool/call", buildJsonObject {
            put("id", "draft")
            put("goal_id", "goal")
            put("execution_started", false)
        })
        ledger.observe("tool/result", buildJsonObject {
            put("id", "draft")
            put("error_code", "TOOL_NOT_STARTED")
            put("is_error", true)
        })
        assertFalse(ledger.hasUnresolvedExternalEffects("goal"))
        assertTrue(ledger.pending().isEmpty())
    }
}
