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
        initial = { 100 },
        reducer = SessionReducer<Int> { state, _ -> state + 100 },
    )
    var differentVersionRejected = false
    runCatching {
        registry.register(
            name = "777/advanced-count",
            stateVersion = 1,
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
    val shared = first.fold(events)

    first.dispose()
    val survivesFirstDispose = registry.names().contains("777/advanced-count")
    second.dispose()
    val removedAfterLastDispose = !registry.names().contains("777/advanced-count")

    val result = buildJsonObject {
        put("projection", buildJsonObject {
            put("stateVersion", shared.stateVersion)
            put("asOfSequence", shared.asOfSequence)
            put("value", shared.state)
            put("sameVersionSharedFirstDefinition", shared.state == 2)
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
