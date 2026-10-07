package com.labteto.dshmobile.reference

import com.labteto.dshmobile.harness.session.SessionEvent
import com.labteto.dshmobile.harness.session.SessionProjectionRegistry
import com.labteto.dshmobile.harness.session.SessionReducer
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main(args: Array<String>) {
    require(args.size == 1) { "usage: AdvancedConformanceMain <output.json>" }

    val registry = SessionProjectionRegistry()
    val first = registry.register(
        name = "777/advanced-count",
        stateVersion = 0,
        initial = { 0 },
        reducer = SessionReducer<Int> { state, _ -> state + 1 },
    )
    val second = registry.register(
        name = "777/advanced-count",
        stateVersion = 0,
        initial = { 999 },
        reducer = SessionReducer<Int> { state, _ -> state + 100 },
    )

    val firstEvents = listOf(
        event(0, "turn/start"),
        event(1, "turn/end"),
    )
    val firstCut = registry.foldSnapshot(firstEvents)
    val sameVersionShared =
        first.fold(firstEvents).state == 2 &&
            second.fold(firstEvents).state == 2 &&
            firstCut.values["777/advanced-count"] == 2

    val differentVersionRejected = runCatching {
        registry.register(
            name = "777/advanced-count",
            stateVersion = 1,
            initial = { 0 },
            reducer = SessionReducer<Int> { state, _ -> state },
        )
    }.isFailure

    first.dispose()
    val secondEvents = firstEvents + event(2, "turn/start")
    val secondCut = registry.foldSnapshot(secondEvents)
    val survivesFirstDispose =
        secondCut.values["777/advanced-count"] == 3

    second.dispose()
    val finalCut = registry.foldSnapshot(secondEvents)
    val removedAfterLastDispose =
        "777/advanced-count" !in finalCut.values

    val result = buildJsonObject {
        put("projection", buildJsonObject {
            put("stateVersionZeroAccepted", true)
            put("sameVersionShared", sameVersionShared)
            put("differentVersionRejected", differentVersionRejected)
            put("asOfSequence", secondCut.asOfSequence)
            put("value", secondCut.values["777/advanced-count"] as? Int ?: -1)
            put("survivesFirstDispose", survivesFirstDispose)
            put("removedAfterLastDispose", removedAfterLastDispose)
        })
    }
    File(args.single()).apply {
        parentFile?.mkdirs()
        writeText(Json { prettyPrint = true }.encodeToString(result))
    }
}

private fun event(sequence: Long, type: String): SessionEvent =
    SessionEvent(
        sequence = sequence,
        type = type,
        createdAt = sequence,
        data = buildJsonObject {},
    )
