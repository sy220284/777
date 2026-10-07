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
        stateVersion = 7,
        initial = { 0 },
        reducer = SessionReducer<Int> { state, _ -> state + 1 },
    )
    val second = registry.register(
        name = "777/advanced-count",
        stateVersion = 7,
        initial = { 999 },
        reducer = SessionReducer<Int> { state, _ -> state + 999 },
    )

    var differentVersionRejected = false
    runCatching {
        registry.register(
            name = "777/advanced-count",
            stateVersion = 8,
            initial = { 0 },
            reducer = SessionReducer<Int> { state, _ -> state },
        )
    }.onFailure {
        differentVersionRejected = true
    }

    val events = listOf(
        SessionEvent(
            sequence = 0,
            type = "turn/start",
            createdAt = 1,
            data = buildJsonObject { put("turn", 1) },
        ),
        SessionEvent(
            sequence = 1,
            type = "turn/end",
            createdAt = 2,
            data = buildJsonObject { put("turn", 1) },
        ),
    )
    val firstSnapshot = first.fold(events)
    val secondSnapshot = second.fold(events)
    val sameVersionShared =
        firstSnapshot.state == 2 &&
            secondSnapshot.state == firstSnapshot.state &&
            registry.names() == listOf("777/advanced-count")

    first.dispose()
    val survivesFirstDispose = registry.names() == listOf("777/advanced-count")
    second.dispose()
    val removedAfterLastDispose = registry.names().isEmpty()

    val result = buildJsonObject {
        put("projection", buildJsonObject {
            put("stateVersion", firstSnapshot.stateVersion)
            put("asOfSequence", firstSnapshot.asOfSequence)
            put("value", firstSnapshot.state)
            put("sameVersionShared", sameVersionShared)
            put("differentVersionRejected", differentVersionRejected)
            put("survivesFirstDispose", survivesFirstDispose)
            put("removedAfterLastDispose", removedAfterLastDispose)
        })
    }
    File(args.single()).apply {
        parentFile?.mkdirs()
        writeText(Json { prettyPrint = true }.encodeToString(result))
    }
}
