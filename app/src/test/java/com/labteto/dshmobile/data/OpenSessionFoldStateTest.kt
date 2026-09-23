package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.SessionEventEnvelope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSessionFoldStateTest {
    @Test
    fun resetKeepsBlankFallbackUntilDurableHistoryExists() {
        val state = OpenSessionFoldState()
        state.reset(blank = true)

        val snapshot = state.rebuild("session", running = null)

        assertTrue(snapshot.blank)
        assertFalse(snapshot.hasMore)
        assertTrue(snapshot.projections.isEmpty())
        assertNull(state.pageAnchor().first)
    }

    @Test
    fun projectionWatermarkRejectsOlderValues() {
        val state = OpenSessionFoldState()
        state.mergeProjection("goal", 9, JsonPrimitive("new"))
        state.mergeProjection("goal", 7, JsonPrimitive("stale"))

        assertEquals(JsonPrimitive("new"), state.projection("goal"))
    }

    @Test
    fun backwardsPagesDeduplicateAndKeepOldestSequence() {
        val state = OpenSessionFoldState()
        state.reset(blank = false)

        state.prependPage(
            listOf(event(20), event(10), event(20)),
            hostHasMore = true,
            overDelivered = false,
        )
        assertEquals(10L, state.pageAnchor().first)

        state.prependPage(
            listOf(event(5), event(10)),
            hostHasMore = false,
            overDelivered = false,
        )
        assertEquals(5L, state.pageAnchor().first)
    }

    @Test
    fun durableAppendCanArriveOutOfOrderWithoutLosingOldestEvent() {
        val state = OpenSessionFoldState()
        state.acceptDurable(event(30))
        state.acceptDurable(event(10))
        state.acceptDurable(event(20))

        assertEquals(10L, state.pageAnchor().first)
    }

    private fun event(seq: Long) = SessionEventEnvelope(
        type = "test/unknown",
        seq = seq,
        time = seq,
        data = buildJsonObject { },
    )
}
