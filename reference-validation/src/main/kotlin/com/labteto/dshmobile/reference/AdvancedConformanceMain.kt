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
    val projection = registry.register(
        name = "777/advanced-count",
        stateVersion = 7,
        initial = { 0 },
        reducer = SessionReducer<Int> { state, _ -> state + 1 },
    )
    var duplicateKeyRejected = false
    runCatching {
        registry.register(
            name = "777/advanced-count",
            stateVersion = 7,
            initial = { 0 },
            reducer = SessionReducer<Int> { state, _ -> state },
        )
    }.onFailure {
        duplicateKeyRejected = true
    }

    val snapshot = projection.fold(
        listOf(
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
        ),
    )

    val result = buildJsonObject {
        put("projection", buildJsonObject {
            put("stateVersion", snapshot.stateVersion)
            put("asOfSequence", snapshot.asOfSequence)
            put("value", snapshot.state)
            put("duplicateKeyRejected", duplicateKeyRejected)
        })
    }
    File(args.single()).apply {
        parentFile?.mkdirs()
        writeText(Json { prettyPrint = true }.encodeToString(result))
    }
}
