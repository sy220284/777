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
    fun duplicateProjectionNameAtSameVersionSharesFirstUnitUntilLastDispose() {
        val registry = SessionProjectionRegistry()
        val first = registry.register(
            name = "chat.relationship",
            stateVersion = 4,
            initial = { 10 },
            reducer = SessionReducer<Int> { state, event ->
                state + (event.data["delta"]?.jsonPrimitive?.intOrNull ?: 0)
            },
        )
        val second = registry.register(
            name = " chat.relationship ",
            stateVersion = 4,
            initial = { 999 },
            reducer = SessionReducer<Int> { state, _ -> state + 999 },
        )

        val events = listOf(event(sequence = 0L, delta = 2))

        assertEquals(12, first.fold(events).state)
        assertEquals(12, second.fold(events).state)
        assertEquals(listOf("chat.relationship"), registry.names())

        first.dispose()
        assertEquals(listOf("chat.relationship"), registry.names())

        second.dispose()
        assertTrue(registry.names().isEmpty())
    }

    @Test
    fun duplicateProjectionNameAtDifferentVersionIsRejected() {
        val registry = SessionProjectionRegistry()
        registry.register(
            name = "chat.relationship",
            stateVersion = 2,
            initial = { 0 },
            reducer = SessionReducer<Int> { state, _ -> state },
        )

        val failure = runCatching {
            registry.register(
                name = "chat.relationship",
                stateVersion = 3,
                initial = { 0 },
                reducer = SessionReducer<Int> { state, _ -> state },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("stateVersion=2"))
    }

    @Test
    fun stateVersionZeroIsAcceptedLikePinnedOfficialRegistry() {
        val registry = SessionProjectionRegistry()
        val projection = registry.register(
            name = "work.zero-version",
            stateVersion = 0,
            initial = { "ok" },
            reducer = SessionReducer<String> { state, _ -> state },
        )

        assertEquals(0, projection.stateVersion)
        assertEquals("ok", projection.fold(emptyList()).state)
    }

    @Test
    fun registrySnapshotUsesOneSharedEventCutAcrossAllUnits() {
        val registry = SessionProjectionRegistry()
        registry.register(
            name = "work.sum",
            stateVersion = 1,
            initial = { 0 },
            reducer = SessionReducer<Int> { state, event ->
                state + (event.data["delta"]?.jsonPrimitive?.intOrNull ?: 0)
            },
        )
        registry.register(
            name = "work.count",
            stateVersion = 7,
            initial = { 0 },
            reducer = SessionReducer<Int> { state, _ -> state + 1 },
        )

        val snapshot = registry.foldSnapshot(
            listOf(
                event(sequence = 2L, delta = 3),
                event(sequence = 5L, delta = 4),
            ),
        )

        assertEquals(5L, snapshot.asOfSequence)
        assertEquals(7, snapshot.values["work.sum"])
        assertEquals(2, snapshot.values["work.count"])
        assertEquals(
            mapOf("work.sum" to 1, "work.count" to 7),
            snapshot.stateVersions,
        )
    }

    private fun event(sequence: Long, delta: Int): SessionEvent =
        SessionEvent(
            sequence = sequence,
            type = "test",
            createdAt = sequence,
            data = buildJsonObject { put("delta", delta) },
        )
}
