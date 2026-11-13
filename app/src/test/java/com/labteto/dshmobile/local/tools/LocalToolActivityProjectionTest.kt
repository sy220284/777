package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.session.SessionEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalToolActivityProjectionTest {
    @Test fun deniedReplacementSettlesItsDeclarationWithoutClaimingExecutionStarted() {
        var state = reduceLocalToolActivity(LocalToolActivityState(), event(1, "tool/call") { put("id", "same") })
        state = reduceLocalToolActivity(state, event(2, "tool/result") { put("id", "same") })
        state = reduceLocalToolActivity(state, event(3, "tool/call") { put("id", "same") })
        state = reduceLocalToolActivity(state, event(4, "tool/authorization-decision") {
            put("id", "same"); put("execution_id", "denied"); put("approved", false)
        })
        state = reduceLocalToolActivity(state, event(5, "tool/result") {
            put("id", "same"); put("tool_declared_sequence", 3L)
            put("is_error", true); put("error_code", "TOOL_DENIED")
        })
        val denied = state.activities.single { it.declaredSequence == 3L }
        assertEquals(LocalToolActivityPhase.FAILED, denied.phase)
        assertEquals(null, denied.startedSequence)
    }

    @Test fun reusedCallIdAndLateReceiptKeepDistinctExecutionFacts() {
        var state = LocalToolActivityState()
        for ((sequence, execution) in listOf(1L to "old", 3L to "new")) {
            state = reduceLocalToolActivity(state, event(sequence, "tool/call") { put("id", "same") })
            state = reduceLocalToolActivity(state, event(sequence + 1, "tool/execution-started") {
                put("id", "same"); put("execution_id", execution)
            })
        }
        state = reduceLocalToolActivity(state, event(5, "tool/result") {
            put("id", "same"); put("execution_id", "old"); put("is_error", false)
        })
        assertEquals(2, state.activities.size)
        assertEquals(LocalToolActivityPhase.COMPLETED, state.activities.single { it.executionId == "old" }.phase)
        assertEquals(LocalToolActivityPhase.RUNNING, state.activities.single { it.executionId == "new" }.phase)
        state = reduceLocalToolActivity(state, event(6, "tool/result") { put("id", "same") })
        assertEquals(LocalToolActivityPhase.RUNNING, state.activities.single { it.executionId == "new" }.phase)
    }

    @Test fun authorizationIsPerExecutionAndDoesNotBecomePermissionForAReplacementCall() {
        var state = reduceLocalToolActivity(LocalToolActivityState(), event(1, "tool/authorization-decision") {
            put("id", "call"); put("name", "mcp_write"); put("execution_id", "exec-original"); put("approved", true)
        })
        assertEquals(true, state.activities.single().authorizationGranted)
        state = reduceLocalToolActivity(state, event(2, "tool/execution-started") {
            put("id", "call"); put("name", "mcp_write"); put("execution_id", "exec-replacement")
        })
        val replacement = state.activities.single { it.executionId == "exec-replacement" }
        assertEquals(null, replacement.authorizationGranted)
        assertEquals(true, state.activities.single { it.executionId == "exec-original" }.authorizationGranted)
    }

    @Test
    fun declarationAdmissionAndResultFoldIntoOneActivity() {
        var state = LocalToolActivityState()
        state = reduceLocalToolActivity(state, event(1, "tool/call") {
            put("id", "c1")
            put("name", "web_search")
            put("execution_started", false)
        })
        assertEquals(LocalToolActivityPhase.DECLARED, state.activities.single().phase)

        state = reduceLocalToolActivity(state, event(2, "tool/execution-started") {
            put("id", "c1")
            put("name", "web_search")
            put("execution_id", "exec-1")
        })
        assertEquals(LocalToolActivityPhase.RUNNING, state.activities.single().phase)
        assertEquals("exec-1", state.activities.single().executionId)

        state = reduceLocalToolActivity(state, event(3, "tool/result") {
            put("id", "c1")
            put("name", "web_search")
            put("is_error", false)
            put("side_effect", "none")
        })
        val result = state.activities.single()
        assertEquals(LocalToolActivityPhase.COMPLETED, result.phase)
        assertEquals(LocalToolActivityKind.SEARCH, result.kind)
        assertEquals(1L, result.declaredSequence)
        assertEquals(2L, result.startedSequence)
        assertEquals(3L, result.finishedSequence)
    }

    @Test
    fun unknownOutcomeIsNotFlattenedIntoGenericFailure() {
        var state = LocalToolActivityState()
        state = reduceLocalToolActivity(state, event(1, "tool/execution-started") {
            put("id", "write-1")
            put("name", "write")
        })
        state = reduceLocalToolActivity(state, event(2, "tool/result") {
            put("id", "write-1")
            put("name", "write")
            put("is_error", true)
            put("error_code", "TOOL_OUTCOME_UNKNOWN")
            put("side_effect", "possible")
        })

        val activity = state.activities.single()
        assertEquals(LocalToolActivityPhase.OUTCOME_UNKNOWN, activity.phase)
        assertEquals("possible", activity.sideEffect)
        assertEquals(LocalToolActivityKind.FILE, activity.kind)
    }

    @Test
    fun resultDetailsKeepBoundedEvidenceAndCancellationPhase() {
        var state = reduceLocalToolActivity(LocalToolActivityState(), event(1, "tool/call") {
            put("id", "details")
            put("name", "read")
            put("arguments", "a".repeat(3_000))
        })
        state = reduceLocalToolActivity(state, event(2, "tool/result") {
            put("id", "details")
            put("content", "x".repeat(5_000))
            put("is_error", true)
            put("error_code", "TOOL_CANCELLED")
        })
        val activity = state.activities.single()
        assertEquals(LocalToolActivityPhase.CANCELLED, activity.phase)
        assertEquals(2L, activity.finishedSequence)
        assertEquals(2_000, activity.argumentsPreview!!.length)
        assertEquals(4_000, activity.resultPreview!!.length)
    }

    @Test
    fun declaredTimestampRemainsStableAcrossExecutionAndResult() {
        var state = reduceLocalToolActivity(LocalToolActivityState(), event(10, "tool/call") {
            put("id", "call"); put("name", "read")
        })
        assertEquals(10L, state.activities.single().declaredAt)
        state = reduceLocalToolActivity(state, event(11, "tool/execution-started") {
            put("id", "call"); put("name", "read")
        })
        state = reduceLocalToolActivity(state, event(12, "tool/result") {
            put("id", "call"); put("name", "read"); put("is_error", false)
        })
        assertEquals(10L, state.activities.single().declaredAt)
    }

    @Test
    fun unresolvedCallsSurviveMoreThanHistoryWindowOfFinishedTools() {
        var state = LocalToolActivityState()
        state = reduceLocalToolActivity(state, event(1, "tool/call") {
            put("id", "slow"); put("name", "read")
        })
        for (i in 2L..290L) {
            state = reduceLocalToolActivity(state, event(i * 2, "tool/result") {
                put("id", "call-$i"); put("name", "read"); put("is_error", false)
            })
        }
        assertEquals(LocalToolActivityPhase.DECLARED,
            state.activities.single { it.callId == "slow" }.phase)
        assertEquals(257, state.activities.size)
    }

    private fun event(
        sequence: Long,
        type: String,
        block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) = SessionEvent(
        sequence = sequence,
        type = type,
        createdAt = sequence,
        data = buildJsonObject(block),
    )
}
