package com.labteto.dshmobile.reference

import com.labteto.dshmobile.harness.session.SessionEvent
import com.labteto.dshmobile.harness.session.SessionProjectionRegistry
import com.labteto.dshmobile.harness.session.SessionReducer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class ProjectionRegistrySemanticFixture(
    val stateVersionZeroAccepted: Boolean,
    val sameVersionShared: Boolean,
    val differentVersionRejected: Boolean,
    val asOfSeqAfterTwoEvents: Long,
    val valueAfterTwoEvents: Int,
    val survivesFirstDispose: Boolean,
    val removedAfterLastDispose: Boolean,
)

class AdvancedSemanticConformanceRunner {
    fun sessionProjectionRegistry(): ProjectionRegistrySemanticFixture {
        val registry = SessionProjectionRegistry()
        val first = registry.register(
            name = "777/reference",
            stateVersion = 0,
            initial = { 0 },
            reducer = SessionReducer<Int> { state, event ->
                state + (event.data["value"]?.jsonPrimitive?.intOrNull ?: 0)
            },
        )
        val second = registry.register(
            name = "777/reference",
            stateVersion = 0,
            initial = { 999 },
            reducer = SessionReducer<Int> { state, event ->
                state + (event.data["value"]?.jsonPrimitive?.intOrNull ?: 0) * 100
            },
        )

        val firstCut = registry.foldSnapshot(listOf(event(0L, 2)))
        val sameVersionShared =
            first.fold(listOf(event(0L, 2))).state == 2 &&
                second.fold(listOf(event(0L, 2))).state == 2 &&
                firstCut.values["777/reference"] == 2

        val differentVersionRejected = runCatching {
            registry.register(
                name = "777/reference",
                stateVersion = 1,
                initial = { 0 },
                reducer = SessionReducer<Int> { state, _ -> state },
            )
        }.isFailure

        first.dispose()
        val secondCut = registry.foldSnapshot(
            listOf(
                event(0L, 2),
                event(1L, 3),
            ),
        )
        val survivesFirstDispose = "777/reference" in secondCut.values

        second.dispose()
        val finalCut = registry.foldSnapshot(
            listOf(
                event(0L, 2),
                event(1L, 3),
            ),
        )

        return ProjectionRegistrySemanticFixture(
            stateVersionZeroAccepted = true,
            sameVersionShared = sameVersionShared,
            differentVersionRejected = differentVersionRejected,
            asOfSeqAfterTwoEvents = secondCut.asOfSequence,
            valueAfterTwoEvents = secondCut.values["777/reference"] as? Int ?: -1,
            survivesFirstDispose = survivesFirstDispose,
            removedAfterLastDispose = "777/reference" !in finalCut.values,
        )
    }

    private fun event(sequence: Long, value: Int): SessionEvent =
        SessionEvent(
            sequence = sequence,
            type = "777/reference-mark",
            createdAt = sequence,
            data = buildJsonObject { put("value", value) },
        )
}
