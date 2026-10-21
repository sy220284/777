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
    @Test
    fun reusedCallIdDoesNotSettleAnEarlierUnknownExecution() {
        val ledger = ToolAttemptLedger()
        ledger.observe("tool/call", buildJsonObject {
            put("id", "reused")
            put("goal_id", "work")
            put("execution_started", false)
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "reused")
            put("execution_id", "execution-old")
        })
        ledger.observe("tool/call", buildJsonObject {
            put("id", "reused")
            put("goal_id", "work")
            put("execution_started", false)
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "reused")
            put("execution_id", "execution-new")
        })
        ledger.observe("tool/result", buildJsonObject {
            put("id", "reused")
            put("execution_id", "execution-new")
            put("is_error", false)
        })
        assertEquals(2, ledger.snapshot().size)
        assertEquals("execution-old", ledger.pending().single().executionId)
        assertTrue(ledger.hasUnresolvedExternalEffects("work"))
    }

    @Test
    fun delayedResultSettlesOnlyMatchingExecutionId() {
        val ledger = ToolAttemptLedger()
        for (id in listOf("execution-a", "execution-b")) {
            ledger.observe("tool/call", buildJsonObject {
                put("id", "same-provider-id")
                put("goal_id", "goal")
                put("execution_started", false)
            })
            ledger.observe("tool/execution-started", buildJsonObject {
                put("id", "same-provider-id")
                put("execution_id", id)
            })
        }
        ledger.observe("tool/result", buildJsonObject {
            put("id", "same-provider-id")
            put("execution_id", "execution-a")
            put("is_error", false)
        })
        assertEquals(listOf("execution-b"), ledger.pending().map { it.executionId })
        assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
        ledger.observe("tool/result", buildJsonObject {
            put("id", "same-provider-id")
            put("execution_id", "execution-b")
            put("is_error", false)
        })
        assertTrue(ledger.pending().isEmpty())
        assertFalse(ledger.hasUnresolvedExternalEffects("goal"))
    }


    @Test
    fun onlyActualAdmissionCanCertifyReadOnlyAndBindRunIdentity() {
        val ledger = ToolAttemptLedger()
        ledger.observe("tool/call", buildJsonObject {
            put("id", "call"); put("goal_id", "goal"); put("execution_started", false)
            put("read_only", true)
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "call"); put("execution_id", "exec")
            put("attributes", buildJsonObject { put("run_id", "run"); put("parent_run_id", "parent") })
        })
        val attempt = ledger.snapshot().single()
        assertEquals(ToolAttemptState.EXTERNAL_PENDING, attempt.state)
        assertEquals("run", attempt.runId)
        assertEquals("parent", attempt.parentRunId)
        assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
    }

    @Test
    fun anUnknownResultCannotBeClearedByAReadOnlyHint() {
        val ledger = ToolAttemptLedger()
        ledger.observe("tool/call", buildJsonObject {
            put("id", "call"); put("goal_id", "goal"); put("execution_started", false)
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "call"); put("read_only", true)
        })
        assertEquals(ToolAttemptState.READING, ledger.snapshot().single().state)
        assertFalse(ledger.hasUnresolvedExternalEffects("goal"))
        ledger.observe("tool/result", buildJsonObject {
            put("id", "call"); put("is_error", true); put("error_code", "TOOL_OUTCOME_UNKNOWN")
        })
        assertEquals(ToolAttemptState.UNKNOWN_EXTERNAL_EFFECT, ledger.snapshot().single().state)
        assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
    }

    @Test
    fun unmatchedOrMisattributedExecutionResultCannotSettleCurrentMutation() {
        val ledger = ToolAttemptLedger()
        ledger.observe("tool/call", buildJsonObject {
            put("id", "call"); put("goal_id", "goal"); put("execution_started", false)
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "call"); put("execution_id", "actual")
        })
        ledger.observe("tool/result", buildJsonObject {
            put("id", "call"); put("execution_id", "foreign"); put("is_error", false)
        })
        ledger.observe("tool/result", buildJsonObject {
            put("id", "different-call"); put("execution_id", "actual"); put("is_error", false)
        })
        assertEquals("actual", ledger.pending().single().executionId)
        assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
        ledger.observe("tool/result", buildJsonObject {
            put("id", "call"); put("execution_id", "actual"); put("is_error", false)
        })
        assertTrue(ledger.pending().isEmpty())
    }


    @Test
    fun receiptWithoutExecutionIdentityDoesNotSettleAnAdmittedMutation() {
        val ledger = ToolAttemptLedger()
        ledger.observe("tool/call", buildJsonObject {
            put("id", "reused")
            put("goal_id", "goal")
            put("execution_started", false)
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "reused")
            put("execution_id", "current-execution")
        })
        // A delayed legacy receipt may have the same provider-scoped call ID.
        ledger.observe("tool/result", buildJsonObject {
            put("id", "reused")
            put("is_error", false)
        })
        assertEquals("current-execution", ledger.pending().single().executionId)
        assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
        ledger.observe("tool/result", buildJsonObject {
            put("id", "reused")
            put("execution_id", "current-execution")
            put("is_error", false)
        })
        assertFalse(ledger.hasUnresolvedExternalEffects("goal"))
    }

    @Test
    fun reusedLegacyCallIdCannotSettleASecondUnidentifiedMutation() {
        val ledger = ToolAttemptLedger()
        repeat(2) {
            ledger.observe("tool/call", buildJsonObject {
                put("id", "same-provider-id")
                put("goal_id", "goal")
                // Original sessions did not persist an execution identity.
            })
        }
        ledger.observe("tool/result", buildJsonObject {
            put("id", "same-provider-id")
            put("is_error", false)
        })
        assertEquals(2, ledger.pending().size)
        assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
    }

    @Test
    fun executionAddressedResultStillSettlesTheCorrectAttemptAfterLegacyReuse() {
        val ledger = ToolAttemptLedger()
        ledger.observe("tool/call", buildJsonObject {
            put("id", "same-provider-id"); put("goal_id", "goal")
        })
        ledger.observe("tool/call", buildJsonObject {
            put("id", "same-provider-id")
            put("goal_id", "goal")
            put("execution_started", false)
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "same-provider-id"); put("execution_id", "new-run-execution")
        })
        ledger.observe("tool/result", buildJsonObject {
            put("id", "same-provider-id")
            put("execution_id", "new-run-execution")
            put("is_error", false)
        })
        assertEquals(1, ledger.pending().size)
        assertEquals(null, ledger.pending().single().executionId)
        assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
        // Delayed receipt without an identity must not settle the old attempt.
        ledger.observe("tool/result", buildJsonObject {
            put("id", "same-provider-id"); put("is_error", false)
        })
        assertEquals(1, ledger.pending().size)
    }

    @Test
    fun uniqueLegacyCallIdKeepsExistingRecoveryCompatibility() {
        val ledger = ToolAttemptLedger()
        ledger.observe("tool/call", buildJsonObject {
            put("id", "unique-legacy")
            put("goal_id", "goal")
        })
        ledger.observe("tool/result", buildJsonObject {
            put("id", "unique-legacy"); put("is_error", false)
        })
        assertTrue(ledger.pending().isEmpty())
        assertFalse(ledger.hasUnresolvedExternalEffects("goal"))
    }

    @Test
    fun foreignExecutionIdentityCannotSettleAStartedLegacyAttempt() {
        val ledger = ToolAttemptLedger()
        ledger.observe("tool/call", buildJsonObject {
            put("id", "legacy")
            put("goal_id", "goal")
        })
        ledger.observe("tool/result", buildJsonObject {
            put("id", "legacy")
            put("execution_id", "unobserved-execution")
            put("is_error", false)
        })
        assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
        assertEquals(1, ledger.pending().size)
        // A genuine legacy receipt with no execution identity remains compatible.
        ledger.observe("tool/result", buildJsonObject {
            put("id", "legacy")
            put("is_error", false)
        })
        assertFalse(ledger.hasUnresolvedExternalEffects("goal"))
    }


    @Test
    fun duplicateAdmissionCannotChangeRunOwnershipOrReadOnlyContract() {
        val ledger = ToolAttemptLedger()
        ledger.observe("tool/call", buildJsonObject {
            put("id", "call"); put("goal_id", "goal"); put("execution_started", false)
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "call"); put("execution_id", "exec")
            put("attributes", buildJsonObject { put("run_id", "original-run") })
            put("read_only", false)
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "call"); put("execution_id", "exec")
            put("attributes", buildJsonObject { put("run_id", "different-run") })
            put("read_only", true)
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "call"); put("read_only", true)
        })
        val attempt = ledger.pending().single()
        assertEquals("original-run", attempt.runId)
        assertEquals("exec", attempt.executionId)
        assertEquals(ToolAttemptState.EXTERNAL_PENDING, attempt.state)
        assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
    }

    @Test
    fun secondExecutionIdentitySettlesSeparatelyWithoutReplacingFirstAdmission() {
        val ledger = ToolAttemptLedger()
        ledger.observe("tool/call", buildJsonObject {
            put("id", "call"); put("goal_id", "goal"); put("execution_started", false)
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "call"); put("execution_id", "first")
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "call"); put("execution_id", "second")
        })
        ledger.observe("tool/result", buildJsonObject {
            put("id", "call"); put("execution_id", "second"); put("is_error", false)
        })
        assertEquals(2, ledger.snapshot().size)
        assertTrue(ledger.snapshot().last().settled)
        assertEquals("first", ledger.pending().single().executionId)
        assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
        ledger.observe("tool/result", buildJsonObject {
            put("id", "call"); put("execution_id", "first"); put("is_error", false)
        })
        assertTrue(ledger.pending().isEmpty())
    }

    @Test
    fun foreignCallCannotReuseAnAlreadyBoundExecutionIdentity() {
        val ledger = ToolAttemptLedger()
        for (id in listOf("first-call", "second-call")) {
            ledger.observe("tool/call", buildJsonObject {
                put("id", id); put("goal_id", "goal"); put("execution_started", false)
            })
        }
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "first-call"); put("execution_id", "shared")
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "second-call"); put("execution_id", "shared")
            put("read_only", true)
        })
        val attempts = ledger.snapshot()
        assertEquals(2, attempts.size)
        assertEquals("shared", attempts[0].executionId)
        assertEquals(null, attempts[1].executionId)
        assertEquals(ToolAttemptState.DECLARED, attempts[1].state)
        ledger.observe("tool/result", buildJsonObject {
            put("id", "second-call"); put("execution_id", "shared"); put("is_error", false)
        })
        assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
        assertEquals("shared", ledger.pending().first().executionId)
    }

    @Test fun distinctAdmissionsWithReusedCallIdRemainSeparateWithoutDeclarations() {
        val ledger = ToolAttemptLedger()
        fun start(execution: String, readOnly: Boolean) {
            ledger.observe("tool/execution-started", buildJsonObject {
                put("id", "reused"); put("execution_id", execution); put("read_only", readOnly)
                put("attributes", buildJsonObject { put("goal_id", "goal"); put("run_id", "run") })
            })
        }
        start("first", true)
        ledger.observe("tool/result", buildJsonObject {
            put("id", "reused"); put("execution_id", "first"); put("is_error", false)
        })
        start("second", false)
        assertEquals(2, ledger.snapshot().size)
        assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
        ledger.observe("tool/result", buildJsonObject {
            put("id", "reused"); put("execution_id", "second")
            put("is_error", true); put("error_code", "TOOL_OUTCOME_UNKNOWN")
        })
        assertEquals(ToolAttemptState.UNKNOWN_EXTERNAL_EFFECT, ledger.snapshot().last().state)
        // A delayed duplicate of the first admission/receipt cannot hide the second.
        start("first", true)
        ledger.observe("tool/result", buildJsonObject {
            put("id", "reused"); put("execution_id", "first"); put("is_error", false)
        })
        assertEquals(2, ledger.snapshot().size)
        assertTrue(ledger.hasUnresolvedExternalEffects("goal"))
    }

}
