package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.session.SessionEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalToolActivityProjectionTest {
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
