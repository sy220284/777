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
    fun projectionRejectsOutOfOrderOrDuplicateEventSequence() {
        val projection = SessionProjection(
            initial = { 0 },
            reducer = SessionReducer<Int> { state, _ -> state + 1 },
        )

        val outOfOrder = runCatching {
            projection.foldSnapshot(
                listOf(
                    event(sequence = 7L, delta = 1),
                    event(sequence = 4L, delta = 1),
                ),
            )
        }.exceptionOrNull()
        val duplicate = runCatching {
            projection.foldSnapshot(
                listOf(
                    event(sequence = 4L, delta = 1),
                    event(sequence = 4L, delta = 1),
                ),
            )
        }.exceptionOrNull()

        assertTrue(outOfOrder is IllegalArgumentException)
        assertTrue(duplicate is IllegalArgumentException)
    }

    @Test
    fun sameVersionRegistrationSharesFirstDefinitionUntilLastDispose() {
        val registry = SessionProjectionRegistry()
        val first = registry.register(
            name = "chat.relationship",
            stateVersion = 0,
            initial = { 0 },
            reducer = SessionReducer<Int> { state, _ -> state + 1 },
        )
        val second = registry.register(
            name = " chat.relationship ",
            stateVersion = 0,
            initial = { 100 },
            reducer = SessionReducer<Int> { state, _ -> state + 100 },
        )
        val events = listOf(event(sequence = 4L, delta = 1))

        assertEquals(1, first.fold(events).state)
        assertEquals(1, second.fold(events).state)
        assertEquals(listOf("chat.relationship"), registry.names())

        first.dispose()
        assertEquals(listOf("chat.relationship"), registry.names())

        second.dispose()
        assertTrue(registry.names().isEmpty())
    }

    @Test
    fun differentVersionRegistrationIsRejectedWithoutReplacingLiveDefinition() {
        val registry = SessionProjectionRegistry()
        val first = registry.register(
            name = "chat.relationship",
            stateVersion = 1,
            initial = { 0 },
            reducer = SessionReducer<Int> { state, _ -> state },
        )

        val failure = runCatching {
            registry.register(
                name = "chat.relationship",
                stateVersion = 2,
                initial = { 1 },
                reducer = SessionReducer<Int> { state, _ -> state },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf("chat.relationship"), registry.names())
        first.dispose()
        assertTrue(registry.names().isEmpty())
    }

    private fun event(sequence: Long, delta: Int): SessionEvent =
        SessionEvent(
            sequence = sequence,
            type = "test",
            createdAt = sequence,
            data = buildJsonObject { put("delta", delta) },
        )
}
