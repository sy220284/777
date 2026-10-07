package com.labteto.dshmobile.harness.session

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProjectionRegistryTest {
    @Test
    fun registeredProjectionCarriesVersionAndExactEventWatermark() {
        val registry = SessionProjectionRegistry()
        val projection = registry.register(
            name = "work.todo",
            stateVersion = 3,
            initial = { 0 },
            reducer = SessionReducer { state, event ->
                state + (event.data["delta"]?.jsonPrimitive?.intOrNull ?: 0)
            },
        )
        val events = listOf(
            event(sequence = 4L, delta = 2),
            event(sequence = 7L, delta = 3),
        )

        val snapshot = projection.fold(events)

        assertEquals(5, snapshot.state)
        assertEquals(7L, snapshot.asOfSequence)
        assertEquals(3, snapshot.stateVersion)
        assertEquals(3, projection.stateVersion)
        assertEquals(listOf("work.todo"), registry.names())
    }

    @Test
    fun emptyProjectionUsesMinusOneWatermark() {
        val projection = SessionProjection(
            initial = { "empty" },
            reducer = SessionReducer<String> { state, _ -> state },
            stateVersion = 2,
        )

        val snapshot = projection.foldSnapshot(emptyList())

        assertEquals("empty", snapshot.state)
        assertEquals(-1L, snapshot.asOfSequence)
        assertEquals(2, snapshot.stateVersion)
    }

    @Test
    fun duplicateProjectionNameIsRejectedWithoutReplacingFirstOwner() {
        val registry = SessionProjectionRegistry()
        registry.register(
            name = "chat.relationship",
            initial = { 0 },
            reducer = SessionReducer<Int> { state, _ -> state },
        )

        val failure = runCatching {
            registry.register(
                name = " chat.relationship ",
                initial = { 1 },
                reducer = SessionReducer<Int> { state, _ -> state },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf("chat.relationship"), registry.names())
    }

    private fun event(sequence: Long, delta: Int): SessionEvent =
        SessionEvent(
            sequence = sequence,
            type = "test",
            createdAt = sequence,
            data = buildJsonObject { put("delta", delta) },
        )
}
