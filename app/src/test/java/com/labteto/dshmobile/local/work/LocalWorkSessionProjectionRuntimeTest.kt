package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.session.SessionProjection
import com.labteto.dshmobile.harness.session.SessionReducer
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkSessionProjectionRuntimeTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun structuredProjectionEventsStayStrictlyIncreasing() {
        val log = LocalSessionEventLog(
            file = File(temporary.root, "projection-order.events.jsonl"),
            json = json,
        )
        try {
            repeat(3) { index ->
                log.append(
                    type = "test/event",
                    data = buildJsonObject { put("index", index) },
                )
            }

            val events = localWorkStructuredProjectionEvents(log)

            assertEquals(listOf(0L, 1L, 2L), events.map { it.sequence })
            val snapshot = SessionProjection(
                initial = { emptyList<Long>() },
                reducer = SessionReducer<List<Long>> { state, event -> state + event.sequence },
            ).foldSnapshot(events)
            assertEquals(listOf(0L, 1L, 2L), snapshot.state)
            assertEquals(2L, snapshot.asOfSequence)
        } finally {
            log.close()
        }
    }
}
